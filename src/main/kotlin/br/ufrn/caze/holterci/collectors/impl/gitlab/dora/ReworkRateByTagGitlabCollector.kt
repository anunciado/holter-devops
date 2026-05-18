
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
package br.ufrn.caze.holterci.collectors.impl.gitlab.dora

import br.com.jadson.snooper.gitlab.data.tag.GitLabTagInfo
import br.com.jadson.snooper.gitlab.operations.GitLabTagQueryExecutor
import br.ufrn.caze.holterci.collectors.Collector
import br.ufrn.caze.holterci.collectors.dtos.CollectResult
import br.ufrn.caze.holterci.domain.models.metric.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Rework Rate collector based on DORA (DevOps Research and Assessment) metrics for GitLab.
 *
 * The **Rework Rate** represents the amount of unplanned deployments performed to fix
 * user-visible bugs, relative to the total deployments performed in a specific period.
 *
 * ## DORA Context:
 * According to the DORA Accelerate 2024 report, the rework rate is an important
 * metric to evaluate deployment quality and identify the need for improvements
 * in the software development and delivery process. It helps teams understand
 * how often their changes cause failures that require immediate fixes.
 *
 * ## Implementation approach:
 * This collector uses GitLab tags as a proxy for deployments, similar to other
 * DORA metrics in the HolterCI system. Tags with specific patterns or labels
 * configured in the project settings can be identified as unplanned/hotfix deployments.
 *
 * ## Formula used:
 * ```
 * Rework Rate = (unplanned deployments / total deployments) × 100
 * ```
 *
 * Where:
 * - **Total deployments**: All tags created in the period (representing deployments)
 * - **Unplanned deployments**: Tags matching configured rework patterns from project configuration
 *
 * ## Configuration:
 * The rework patterns are configured per project in the Main Repository settings under
 * "Tags Rework Labels" field. Examples: "hotfix,patch,fix,urgent,emergency,critical"
 *
 * ## Examples:
 * - Total tags in period: 20
 * - Hotfix tags: 3 (v1.2.1-hotfix, v1.3.2-patch, v1.4.1-fix)
 * - Rework Rate = (3 / 20) × 100 = 15.00%
 *
 * @author jadson santos - jadsonjs@gmail.com
 * @author luís anunciado - luis.eduardo.225@gmail.com
 */
@Component
class ReworkRateByTagGitlabCollector
    : Collector(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"), Metric.REWORK_RATE, "Rework Rate Calculator at GitLab", MetricRepository.GITLAB) {

    companion object {
        private const val PERCENTAGE_SCALE = 2
        private const val PERCENTAGE_MULTIPLIER = 100
        
        /**
         * Default patterns used when no project configuration is available.
         * These patterns are commonly used to indicate hotfixes, patches, or urgent fixes.
         */
        private val DEFAULT_REWORK_PATTERNS = listOf(
            "hotfix",
            "patch",
            "fix", 
            "urgent",
            "emergency",
            "critical"
        )
    }

    override fun calcMetricValue(period: Period, globalPeriod: Period, project: Project): CollectResult {
        val projectConfiguration = projectRepository.findConfigurationByIdProject(project.id!!)

        val executor = GitLabTagQueryExecutor()
        executor.setGitlabURL(projectConfiguration.mainRepository.url)
        executor.setGitlabToken(projectConfiguration.mainRepository.token)
        executor.setDisableSslVerification(disableSslVerification)

        // Get all tags for the project (representing deployments)
        val allTags: MutableList<GitLabTagInfo> = executor.tags("${project.organization}/${project.name}")

        // Filter tags within the analysis period
        val tagsOfPeriod: MutableList<GitLabTagInfo> = arrayListOf()
        for (tag in allTags) {
            if (tag.commit.created_at != null &&
                dateUtil.isBetweenDates(
                    dateUtil.toLocalDateTime(tag.commit.created_at),
                    period.init,
                    period.end
                )
            ) {
                tagsOfPeriod.add(tag)
            }
        }

        // Identify rework/unplanned deployments based on configured patterns
        val reworkTags = identifyReworkTags(tagsOfPeriod, projectConfiguration.mainRepository.tagsReworkLabels)

        // Calculate rework rate
        val reworkRate = calculateReworkRate(tagsOfPeriod.size, reworkTags.size)

        return CollectResult(
            reworkRate,
            generateMetricInfo(period, tagsOfPeriod, reworkTags, projectConfiguration.mainRepository.tagsReworkLabels),
            null
        )
    }

    /**
     * Identifies tags that represent rework/unplanned deployments based on configured patterns.
     *
     * @param tags List of tags to analyze
     * @param configuredPatterns Comma-separated patterns from project configuration
     * @return List of tags identified as rework deployments
     */
    private fun identifyReworkTags(tags: List<GitLabTagInfo>, configuredPatterns: String?): List<GitLabTagInfo> {
        val patterns = getReworkPatterns(configuredPatterns)
        
        return tags.filter { tag ->
            val tagNameLower = tag.name.lowercase()
            patterns.any { pattern -> tagNameLower.contains(pattern.lowercase()) }
        }
    }

    /**
     * Gets the rework patterns from project configuration or uses defaults.
     *
     * @param configuredPatterns Comma-separated patterns from project configuration
     * @return List of patterns to match against tag names
     */
    private fun getReworkPatterns(configuredPatterns: String?): List<String> {
        return if (configuredPatterns.isNullOrBlank()) {
            DEFAULT_REWORK_PATTERNS
        } else {
            configuredPatterns.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /**
     * Calculates the rework rate as a percentage.
     *
     * @param totalDeployments Total number of deployments (tags) in the period
     * @param reworkDeployments Number of rework deployments identified
     * @return Rework rate as BigDecimal with 2 decimal places
     */
    private fun calculateReworkRate(totalDeployments: Int, reworkDeployments: Int): BigDecimal {
        if (totalDeployments == 0 || reworkDeployments == 0) {
            return BigDecimal.ZERO.setScale(PERCENTAGE_SCALE, RoundingMode.HALF_UP)
        }

        val rework = BigDecimal.valueOf(reworkDeployments.toLong())
        val total = BigDecimal.valueOf(totalDeployments.toLong())
        val multiplier = BigDecimal.valueOf(PERCENTAGE_MULTIPLIER.toLong())

        return rework
            .divide(total, PERCENTAGE_SCALE + 2, RoundingMode.HALF_UP)
            .multiply(multiplier)
            .setScale(PERCENTAGE_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * Generates detailed metric information for reporting purposes.
     *
     * @param period Analysis period
     * @param tagsOfPeriod All tags in the period
     * @param reworkTags Tags identified as rework deployments
     * @param configuredPatterns The configured patterns used for identification
     * @return Formatted HTML string with metric details
     */
    private fun generateMetricInfo(
        period: Period,
        tagsOfPeriod: List<GitLabTagInfo>,
        reworkTags: List<GitLabTagInfo>,
        configuredPatterns: String?
    ): String {
        val dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val patterns = getReworkPatterns(configuredPatterns)

        return "<strong> [${dtf.format(period.init)} to ${dtf.format(period.end)}] </strong> <br>" +
                "  - Total Deployments (Tags): ${tagsOfPeriod.size} <br>" +
                "  - Rework Deployments: ${reworkTags.size} <br>" +
                "  - Rework Rate: ${calculateReworkRate(tagsOfPeriod.size, reworkTags.size)}% <br>" +
                "  - Rework Patterns Used: ${patterns.joinToString(", ")} <br>" +
                "  - All Tags: ${tagsOfPeriod.joinToString("; ") { it.name }} <br>" +
                "  - Rework Tags: ${reworkTags.joinToString("; ") { it.name }} <br>" +
                "  - Tag Dates: ${tagsOfPeriod.joinToString("; ") {
                    dateUtil.format(dateUtil.toLocalDateTime(it.commit.created_at), "dd/MM/yyyy")
                }} <br>"
    }

    override fun cleanCache() {
        // No cache implementation needed for this collector
    }
}