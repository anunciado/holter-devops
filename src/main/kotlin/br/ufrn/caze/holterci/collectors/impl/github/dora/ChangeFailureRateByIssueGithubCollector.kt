/*
 * Federal University of Rio Grande do Norte
 * Department of Informatics and Applied Mathematics
 * Collaborative & Automated Software Engineering (CASE) Research Group
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */
package br.ufrn.caze.holterci.collectors.impl.github.dora

import br.com.jadson.snooper.github.data.issue.GitHubIssueInfo
import br.com.jadson.snooper.github.data.release.GitHubReleaseInfo
import br.com.jadson.snooper.github.operations.IssueQueryExecutor
import br.com.jadson.snooper.github.operations.ReleaseQueryExecutor
import br.ufrn.caze.holterci.collectors.Collector
import br.ufrn.caze.holterci.collectors.dtos.CollectResult
import br.ufrn.caze.holterci.domain.models.metric.*
import br.ufrn.caze.holterci.domain.utils.GitHubUtil
import br.ufrn.caze.holterci.domain.utils.LabelsUtil
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Change failure rate is how often a change cause failure in production.
 *
 * In GitHub, Change failure rate can be measured as the percentage of deployments that cause an incident in production
 * in the given time period. Similar to GitLab's approach, we calculate this by the number of incidents divided by 
 * the number of deployments to a production environment.
 *
 * This assumes:
 * - GitHub issues are tracked with error-related labels
 * - All incidents are related to a production environment
 * - Incidents and deployments have a relationship with releases
 *
 * To simplify we will calculate the number of issues "of error" that were closed for a project,
 * dividing by the total amount of issues closed in the period,
 * dividing by the amount of releases for the project (GitHub releases)
 *
 * ----------------------------------------------------------------------------------------------------------------------------------
 * Why?  Because:  If we have few error issues compared with the total of issues in a version, this is not a problem.
 *                 The problem is if we have a relative qty of error issues compared with the amount of issues closed in version.
 *                 The system is generating a lot of errors.
 * ----------------------------------------------------------------------------------------------------------------------------------
 *
 * This approach uses GitHub Issues API to get issues with error labels and GitHub Releases API to get release information.
 * This way we don't need to create incidents, normal issues work. Incidents don’t need one-to-one relation with deploy.
 *
 * Change Failure Rate = ( ( qty Error Issues / qty Issues ) / qty of releases ) * 100
 *
 * @author jadson santos - jadsonjs@gmail.com
 * @author luís anunciado - luis.eduardo.225@gmail.com
 */
@Component
class ChangeFailureRateByIssueGithubCollector
    : Collector(UUID.fromString("6113a427-9742-4a20-8b6c-fa052bdce13f"), Metric.CHANGE_FAILURE_RATE, "Change Failure Rate By Issue at GitHub", MetricRepository.GITHUB) {

    @Autowired
    lateinit var gitHubUtils: GitHubUtil

    /** cache all issues of a project, because this query can be slow */
    var issuesCache = mutableListOf<GitHubIssueInfo>()

    override fun calcMetricValue(period: Period, globalPeriod: Period, project: Project): CollectResult {
        val projectConfiguration = projectRepository.findConfigurationByIdProject(project.id!!)

        /////////////////////// Get Issues of error ////////////////////////

        val executor = IssueQueryExecutor()
        executor.setGithubToken(projectConfiguration.mainRepository.token)
        // GitHub API uses different query parameters: state=closed, labels for filtering
        executor.setQueryParameters(arrayOf("state=closed", "labels="+projectConfiguration.mainRepository.issuesErrosLabels))
        executor.setPageSize(100)

        // bring all issues first time to memory
        if(issuesCache.isEmpty()){
            val allIssues = executor.issuesClosedInPeriod(project.organization + "/" +project.name, globalPeriod.init, globalPeriod.end)
            issuesCache = allIssues.toMutableList()
        }
        
        // Filter issues closed in the specific period using GitHub utility methods
        var issuesInPeriod = gitHubUtils.getIssuesClosedInPeriod(issuesCache, period.init, period.end)
        var errorIssuesInPeriod = mutableListOf<GitHubIssueInfo>()
        
        for (issue in issuesInPeriod){
            if(isErrorIssue(issue, projectConfiguration.mainRepository.issuesErrosLabels))
                errorIssuesInPeriod.add(issue)
        }

        /////////////////////// Get Releases of period ////////////////////////

        val executorReleases = ReleaseQueryExecutor()
        executorReleases.setGithubToken(projectConfiguration.mainRepository.token)

        /**
         * This will consider a release as a GitHub release, which is more structured than tags
         * GitHub releases provide better metadata and are the recommended approach for production deployments
         */
        val allReleases: MutableList<GitHubReleaseInfo> = executorReleases.releases(project.organization + "/" + project.name).toMutableList()

        var releasesOfPeriod: MutableList<GitHubReleaseInfo> = arrayListOf()

        // filter releases by period using published_at date
        for(release in allReleases){
            if(release.published_at != null) {
                val releaseDate = LocalDateTime.ofInstant(release.published_at.toInstant(), ZoneId.systemDefault())
                if(dateUtil.isBetweenDates(releaseDate, period.init, period.end)) {
                    releasesOfPeriod.add(release)
                }
            }
        }

        ////////////////////// Calc Metric /////////////////////////

        if(errorIssuesInPeriod.size == 0)
            return CollectResult(BigDecimal.ZERO, "", null)

        if(releasesOfPeriod.size > 0) { // there are releases in this period, divide by the number of releases
            return CollectResult( 
                ( errorIssuesInPeriod.size.toBigDecimal().divide(issuesInPeriod.size.toBigDecimal()).divide(releasesOfPeriod.size.toBigDecimal()) ).multiply(BigDecimal(100)), 
                generateMetricInfo(period, errorIssuesInPeriod, releasesOfPeriod), 
                null 
            )
        } else {
            return CollectResult( 
                ( errorIssuesInPeriod.size.toBigDecimal().divide(issuesInPeriod.size.toBigDecimal()) ).multiply(BigDecimal(100)), 
                generateMetricInfo(period, errorIssuesInPeriod, releasesOfPeriod), 
                null 
            )
        }
    }

    override fun cleanCache() {
        issuesCache.clear()
    }

    @Autowired
    lateinit var labelsUtil : LabelsUtil

    /**
     * Check if an issue is an error issue by analyzing its labels against the configured error labels
     * Uses the GitHub Issues API label structure where labels are objects with name property
     */
    private fun isErrorIssue(issue: GitHubIssueInfo, errorsWord : String?): Boolean {
        if (issue.labels != null && issue.labels.size > 0) {
            for (label in issue.labels) {
                // GitHub labels have a 'name' property, different from GitLab's string array
                if (labelsUtil.isErrorLabels(label.name, errorsWord)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Generate detailed metric information for reporting purposes
     * Provides comprehensive details about the calculation including issue counts, statuses, and release information
     */
    fun generateMetricInfo(period: Period, errorIssuesInPeriod : List<GitHubIssueInfo>, releasesOfPeriod : List<GitHubReleaseInfo>): String{

        val statusCountMap = mutableMapOf<String, Int>()
        var issueUniqueNumbers = mutableSetOf<Int>()

        for (issue in errorIssuesInPeriod) {
            // Count occurrences of each status
            statusCountMap[issue.state] = statusCountMap.getOrDefault(issue.state, 0) + 1
            issueUniqueNumbers.add(issue.number)
        }

        val dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        return  "<strong> [ "+ dtf.format(period.init ) +" to "+ dtf.format(period.end) + "] </strong> <br> "+
                "  - Error Issues: "+errorIssuesInPeriod.size+" <br>"+
                "  - Issues Status: "+statusCountMap+" <br>"+
                "  - Issues Numbers: "+issueUniqueNumbers+" <br>"+
                "  - Releases: "+releasesOfPeriod.size+" <br>"+
                "  - Release Names: "+releasesOfPeriod.joinToString("; "){ it.name ?: "unnamed" }+" <br>"+
                "  - Release Dates: "+releasesOfPeriod.joinToString("; "){ 
                    if(it.published_at != null) 
                        dateUtil.format(LocalDateTime.ofInstant(it.published_at.toInstant(), ZoneId.systemDefault()), "dd/MM/yyyy") 
                    else "unpublished" 
                }+" <br>"
    }
}