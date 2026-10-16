package com.carequeue.dto;

import lombok.*;

import java.util.List;
import java.util.Map;

public class AnalyticsDtos {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalyticsOverviewResponse {
        private long totalAppointmentsToday;
        private long completedToday;
        private long waitingNow;
        private long cancelledToday;
        private long noShowsToday;
        private long emergencyCasesToday;
        private double averageWaitMinutes;
        private Double doctorUtilizationPercentage; // null = not tracked by the system
        private List<DepartmentStatDto> departmentDistribution;
        private List<HourlyLoadDto> hourlyCheckIns;
        private List<DoctorPerformanceDto> doctorUtilization;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DepartmentStatDto {
        private String departmentName;
        private long count;
        private double percentage;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HourlyLoadDto {
        private String hourLabel; // "09:00", "10:00", etc.
        private long checkIns;
        private Long consultations; // null = not tracked by the system
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoctorPerformanceDto {
        private Long doctorId;
        private String doctorName;
        private String departmentName;
        private long completedPatients;
        private long totalAssigned;
        private double averageConsultationMinutes;
    }
}
