package com.deployhub.platform.controllers;

import com.deployhub.platform.models.DTO.DashboardSummaryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentMetricsResponseDTO;
import com.deployhub.platform.services.Interface.IDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final IDashboardService dashboardService;

    @GetMapping("/summary")
    public DashboardSummaryResponseDTO summary() {
        return dashboardService.summary();
    }

    /** Queue and throughput figures for the last hour: your deployments, plus platform-wide queue depth. */
    @GetMapping("/metrics")
    public DeploymentMetricsResponseDTO metrics() {
        return dashboardService.metrics();
    }
}
