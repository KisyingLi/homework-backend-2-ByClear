package com.example.demo.repository;

import com.example.demo.entity.RewardRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RewardRecordRepository extends JpaRepository<RewardRecord, Long> {
    Optional<RewardRecord> findByUserIdAndActivityId(Long userId, Long activityId);
}
