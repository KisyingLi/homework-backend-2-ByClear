package com.example.demo.model;

import com.example.demo.enums.MissionType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "activity_missions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityMissionModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id", nullable = false)
    @ToString.Exclude
    @JsonBackReference
    private ActivityMasterModel activityMaster;

    @Enumerated(EnumType.STRING)
    @Column(name = "mission_type", nullable = false)
    private MissionType missionType;

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
