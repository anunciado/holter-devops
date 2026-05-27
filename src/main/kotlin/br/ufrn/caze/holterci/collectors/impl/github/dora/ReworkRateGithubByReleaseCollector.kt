package br.ufrn.caze.holterci.collectors.impl.github.dora;

import br.com.jadson.snooper.github.data.release.GitHubReleaseInfo
import br.com.jadson.snooper.github.operations.ReleaseQueryExecutor
import br.ufrn.caze.holterci.collectors.Collector
import br.ufrn.caze.holterci.collectors.dtos.CollectResult
import br.ufrn.caze.holterci.domain.models.metric.*
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Rework Rate collector based on DORA (DevOps Research and Assessment) metrics for GitHub.
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
 * ## Implementation approach for GitHub:
 * This collector uses GitHub releases as a proxy for deployments, similar to other
 * DORA metrics in the HolterCI system. Releases with specific patterns or labels
 * configured in the project settings can be identified as unplanned/hotfix deployments.
 *
 * ## Formula used:
 * ```
 * Rework Rate = (unplanned deployments / total deployments) × 100
 * ```
 *
 * Where:
 * - **Total deployments**: All releases created in the period (representing deployments)
 * - **Unplanned deployments**: Releases matching configured rework patterns from project configuration
 *
 * ## Configuration:
 * The rework patterns are configured per project in the Main Repository settings under
 * "Tags Rework Labels" field. Examples: "hotfix,patch,fix,urgent,emergency,critical"
 *
 * ## Examples:
 * - Total releases in period: 20
 * - Hotfix releases: 3 (v1.2.1-hotfix, v1.3.2-patch, v1.4.1-fix)
 * - Rework Rate = (3 / 20) × 100 = 15.00%
 *
 * ## GitHub-specific implementation notes:
 * Unlike GitLab tags, this collector uses GitHub releases which provide richer metadata
 * and are specifically designed to mark deployment milestones. The collector examines
 * both release names and tags to identify rework patterns. It leverages the GitHub
 * Releases API through the Snooper framework's ReleaseQueryExecutor.
 *
 * @author jadson santos - jadsonjs@gmail.com
 * @author luís anunciado - luis.eduardo.225@gmail.com
 */
@Component
class ReworkRateGithubByReleaseCollector
    : Collector(UUID.fromString("8aecc733-ebb7-427d-b41a-31daff8ce616"), Metric.REWORK_RATE, "Rework Rate by Tag at Github", MetricRepository.GITHUB){

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

        // Use GitHub Releases API through Snooper framework
        val executor = ReleaseQueryExecutor()
        executor.setGithubToken(projectConfiguration.mainRepository.token)

        // Get all releases for the project (representing deployments)
        val allReleases: MutableList<GitHubReleaseInfo> = executor.releases("${project.organization}/${project.name}")

        // Filter releases within the analysis period
        val releasesOfPeriod: MutableList<GitHubReleaseInfo> = arrayListOf()
        for (release in allReleases) {
            if (release.published_at != null &&
                dateUtil.isBetweenDates(
                    dateUtil.toLocalDateTime(release.published_at),
                    period.init,
                    period.end
                )
            ) {
                releasesOfPeriod.add(release)
            }
        }

        // Identify rework/unplanned deployments based on configured patterns
        val reworkReleases = identifyReworkReleases(releasesOfPeriod, projectConfiguration.mainRepository.tagsReworkLabels)

        // Calculate rework rate
        val reworkRate = calculateReworkRate(releasesOfPeriod.size, reworkReleases.size)

        return CollectResult(
            reworkRate,
            generateMetricInfo(period, releasesOfPeriod, reworkReleases, projectConfiguration.mainRepository.tagsReworkLabels),
            null
        )
    }

    /**
     * Identifies releases that represent rework/unplanned deployments based on configured patterns.
     * 
     * This method examines both release names and tag names for pattern matching, providing
     * comprehensive coverage for different GitHub project naming conventions.
     *
     * @param releases List of releases to analyze
     * @param configuredPatterns Comma-separated patterns from project configuration
     * @return List of releases identified as rework deployments
     */
    private fun identifyReworkReleases(releases: List<GitHubReleaseInfo>, configuredPatterns: String?): List<GitHubReleaseInfo> {
        val patterns = getReworkPatterns(configuredPatterns)
        
        return releases.filter { release ->
            val releaseNameLower = release.name?.lowercase() ?: ""
            val tagNameLower = release.tag_name?.lowercase() ?: ""
            
            patterns.any { pattern -> 
                releaseNameLower.contains(pattern.lowercase()) || 
                tagNameLower.contains(pattern.lowercase())
            }
        }
    }

    /**
     * Gets the rework patterns from project configuration or uses defaults.
     *
     * @param configuredPatterns Comma-separated patterns from project configuration
     * @return List of patterns to match against release names and tags
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
     * @param totalDeployments Total number of deployments (releases) in the period
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
     * This method provides comprehensive information about the analysis including
     * all releases found, those identified as rework, and the patterns used for identification.
     *
     * @param period Analysis period
     * @param releasesOfPeriod All releases in the period
     * @param reworkReleases Releases identified as rework deployments
     * @param configuredPatterns The configured patterns used for identification
     * @return Formatted HTML string with metric details
     */
    private fun generateMetricInfo(
        period: Period,
        releasesOfPeriod: List<GitHubReleaseInfo>,
        reworkReleases: List<GitHubReleaseInfo>,
        configuredPatterns: String?
    ): String {
        val dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val patterns = getReworkPatterns(configuredPatterns)

        return "<strong> [${dtf.format(period.init)} to ${dtf.format(period.end)}] </strong> <br>" +
                "  - Total Deployments (Releases): ${releasesOfPeriod.size} <br>" +
                "  - Rework Deployments: ${reworkReleases.size} <br>" +
                "  - Rework Rate: ${calculateReworkRate(releasesOfPeriod.size, reworkReleases.size)}% <br>" +
                "  - Rework Patterns Used: ${patterns.joinToString(", ")} <br>" +
                "  - All Releases: ${releasesOfPeriod.joinToString("; ") { it.name ?: it.tag_name ?: "unnamed" }} <br>" +
                "  - Rework Releases: ${reworkReleases.joinToString("; ") { it.name ?: it.tag_name ?: "unnamed" }} <br>" +
                "  - Release Dates: ${releasesOfPeriod.joinToString("; ") {
                    if (it.published_at != null) {
                        dateUtil.format(dateUtil.toLocalDateTime(it.published_at), "dd/MM/yyyy")
                    } else {
                        "no date"
                    }
                }} <br>"
    }

    override fun cleanCache() {
        // No cache implementation needed for this collector
        // GitHub API calls are managed by the Snooper framework
    }
}