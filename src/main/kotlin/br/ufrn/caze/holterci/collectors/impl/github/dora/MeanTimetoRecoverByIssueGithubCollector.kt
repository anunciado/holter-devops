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
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.*

/**
 * Mean Time to Recovery: Measures the time between an interruption due to deployment or system failure and full recovery.
 *
 * In GitHub, Time to restore service (Mean Time to Recovery) is measured as the median time an incident was open for on a production environment.
 * GitHub calculates the number of seconds an incident was open on a production environment in the given time period. This assumes:
 *
 *     GitHub issues are tracked for incidents.
 *     All incidents are related to a production environment.
 *     Issues and deployments have a strictly one-to-one relationship. An issue is related to only one production deployment,
 *     and any production deployment is related to no more than one issue
 *
 *
 * GitHub makes many assumptions that are difficult to use in a project. ''an incident'' is a special kind of issue.
 * So, to simplify we will calculate the median time of issues "of error" was open and closed for a project.
 * dividing by amount of releases for the project
 *
 * This way we do not need to create incidents, normal issues work. incidents do not need relation one-to-one with deploy.
 *
 * **Calculation using Snooper:**
 * This collector uses GitHub's Issues API through the snooper library to:
 * 1. Fetch all closed issues with error labels in the specified period
 * 2. Calculate the time each error issue remained open (closed_at - created_at)
 * 3. Get all releases in the period to normalize the metric
 * 4. Calculate the mean time divided by number of releases
 *
 * Mean Time to Recovery = Mean ( SUM Issues ( issue close date - issues open date) ) / qty of releases
 *
 * @author jadson santos - jadsonjs@gmail.com
 * @author luís anunciado - luis.eduardo.225@gmail.com
 */
@Component
class MeanTimetoRecoverByIssueGithubCollector
    : Collector(UUID.fromString("898f3976-8a1a-465a-9bde-77f94f82d320"), Metric.MEAN_TIME_TO_RECOVERY, "Mean time to Recover By Issue at GitHub", MetricRepository.GITHUB) {

    @Autowired
    lateinit var githubUtil: GitHubUtil

    /** cache all issues of a project, because this query is very slow */
    var issuesCache = mutableListOf<GitHubIssueInfo>()

    override fun calcMetricValue(period: Period, globalPeriod: Period, project: Project): CollectResult {

        val projectConfiguration = projectRepository.findConfigurationByIdProject(project.id!!)

        ///////////////////////  Get Issues of error using GitHub API ////////////////////////

        val executor = IssueQueryExecutor()
        executor.setGithubToken(projectConfiguration.mainRepository.token)
        executor.setQueryParameters(arrayOf("state=closed", "labels="+projectConfiguration.mainRepository.issuesErrosLabels))
        executor.setPageSize(100)

        // bring all issues first time to memory
        if(issuesCache.isEmpty()){
            var allIssues = executor.issuesClosedInPeriod(project.organization + "/" + project.name, globalPeriod.init, globalPeriod.end)
            issuesCache = allIssues.toMutableList()
        }
        var issuesInPeriod = githubUtil.getIssueClosedInPeriod(issuesCache, period.init, period.end)
        var errorIssuesInPeriod = mutableListOf<GitHubIssueInfo>()
        for (issue in issuesInPeriod){
            if(isErrorIssue(issue, projectConfiguration.mainRepository.issuesErrosLabels))
                errorIssuesInPeriod.add(issue)
        }

        ///////////////////////  Get Releases of period using GitHub API ////////////////////////

        val executorReleases = ReleaseQueryExecutor()
        executorReleases.setGithubToken(projectConfiguration.mainRepository.token)

        /**
         * This will consider a release using GitHub's releases API
         */
        var allReleases: MutableList<GitHubReleaseInfo> = executorReleases.releases(project.organization + "/" + project.name).toMutableList()

        var releasesOfPeriod: MutableList<GitHubReleaseInfo> = arrayListOf()

        // for each release
        for(r in allReleases){
            if( r.created_at != null && dateUtil.isBetweenDates(r.created_at.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), period.init, period.end) ) {
                releasesOfPeriod.add(r)
            }
        }

        ////////////////////// Calc Metric /////////////////////////

        var timeCloseErrors: MutableList<BigDecimal> = mutableListOf()
        for(error in errorIssuesInPeriod){
            if(error.created_at != null && error.closed_at != null) {
                val createdDate = error.created_at.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                val closedDate = error.closed_at.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                timeCloseErrors.add(dateUtil.daysBetween(createdDate, closedDate).toBigDecimal())
            }
        }

        if(releasesOfPeriod.size > 0) { // there are releases in this period, divide by the number of releases
            return CollectResult(mathUtil.meanOfValues(timeCloseErrors).divide(releasesOfPeriod.size.toBigDecimal()), generateMetricInfo(period, errorIssuesInPeriod, releasesOfPeriod), null)
        }else{
            return CollectResult(mathUtil.meanOfValues(timeCloseErrors), generateMetricInfo(period, errorIssuesInPeriod, releasesOfPeriod), null )
        }

    }

    override fun cleanCache() {
        issuesCache.clear()
    }

    @Autowired
    lateinit var labelsUtil : LabelsUtil

    private fun isErrorIssue(issue: GitHubIssueInfo, errorsWord : String?): Boolean {
        if (issue.labels != null && issue.labels.isNotEmpty()) {
            for (label in issue.labels) {
                if (labelsUtil.isErrorLabels(label.name, errorsWord)) {
                    return true
                }
            }
        }
        return false
    }

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
                    if(it.created_at != null) dateUtil.format(it.created_at.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), "dd/MM/yyyy") else "no date" 
                }+" <br>"

    }

}