package com.example.demo.repository;

import com.example.demo.model.GameLaunchRecordModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GameLaunchRecordRepository extends JpaRepository<GameLaunchRecordModel, Long> {
    List<GameLaunchRecordModel> findByUserId(Long userId);
}
