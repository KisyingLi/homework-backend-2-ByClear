package com.example.demo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "game_launch_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameLaunchRecordModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @CreationTimestamp
    @Column(name = "launched_at", updatable = false)
    private LocalDateTime launchedAt;
}
