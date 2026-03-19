package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "reward_records", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "activity_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewardRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "reward_points", nullable = false)
    private Integer rewardPoints;

    @CreationTimestamp
    @Column(name = "claimed_at", updatable = false)
    private LocalDateTime claimedAt;
}
