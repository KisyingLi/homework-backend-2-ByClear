package com.example.demo.repository;

import com.example.demo.model.RewardRecordModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RewardRecordRepository extends JpaRepository<RewardRecordModel, Long> {
    Optional<RewardRecordModel> findByUserIdAndActivityId(Long userId, Long activityId);
}
