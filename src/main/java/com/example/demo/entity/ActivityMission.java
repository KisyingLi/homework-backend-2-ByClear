package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "activity_missions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityMission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @ToString.Exclude
    private ActivityMaster activityMaster;

    @Column(name = "mission_order", nullable = false)
    private Integer missionOrder;

    @Column(name = "mission_name", nullable = false, length = 100)
    private String missionName;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "target_score")
    @Builder.Default
    private Integer targetScore = 0;

    @Column(length = 255)
    private String description;
}
