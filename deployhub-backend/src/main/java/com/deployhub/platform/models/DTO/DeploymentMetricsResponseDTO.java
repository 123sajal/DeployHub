package com.deployhub.platform.models.DTO;

/**
 * Dashboard metrics over the last {@code windowMinutes}. Everything is about the current user's own
 * deployments except {@code platformQueue}, which is the shared queue's depth.
 *
 * @param avgQueueWaitMs average time from request until a worker picked a deployment up; null if none started
 * @param maxQueueWaitMs longest such wait; null if none started
 * @param finished       deployments that reached a final state (succeeded + failed + cancelled) = throughput
 * @param succeeded      went LIVE (including ones replaced since)
 * @param successRate    succeeded / (succeeded + failed), 0..1; null when nothing succeeded or failed
 */
public record DeploymentMetricsResponseDTO(
        int windowMinutes,
        Queue platformQueue,
        Long avgQueueWaitMs,
        Long maxQueueWaitMs,
        long finished,
        long succeeded,
        long failed,
        long cancelled,
        Double successRate) {

    /**
     * @param waiting        due jobs waiting for a free worker
     * @param retryScheduled jobs waiting for a scheduled retry
     * @param running        jobs a worker is running now
     */
    public record Queue(long waiting, long retryScheduled, long running) {
    }
}
