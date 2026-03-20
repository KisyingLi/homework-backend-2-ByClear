package com.example.demo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "games_play_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayRecordModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "game_id", nullable = false)
    private Long gameId;

    @Column(nullable = false)
    private Integer score;

    @CreationTimestamp
    @Column(name = "played_at", updatable = false)
    private LocalDateTime playedAt;
}
