package com.example.demo.repository;

import com.example.demo.entity.ActivityMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActivityMissionRepository extends JpaRepository<ActivityMission, Long> {
}
