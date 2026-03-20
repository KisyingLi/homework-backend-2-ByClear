package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "activity_master")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityMasterModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_key", unique = true, nullable = false, length = 50)
    private String activityKey;

    @Column(name = "activity_name", nullable = false, length = 100)
    private String activityName;

    @Column(name = "days_limit", nullable = false)
    @Builder.Default
    private Integer daysLimit = 30;

    @Column(name = "total_reward", nullable = false)
    @Builder.Default
    private Integer totalReward = 0;

    @Column(length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "activityMaster", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    @JsonManagedReference
    private List<ActivityMissionModel> missions;
}
