package com.deployhub.platform.services.Interface;

import com.deployhub.platform.models.DTO.DashboardSummaryResponseDTO;
import com.deployhub.platform.models.DTO.DeploymentMetricsResponseDTO;

public interface IDashboardService {

    DashboardSummaryResponseDTO summary();

    DeploymentMetricsResponseDTO metrics();
}
