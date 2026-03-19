package com.example.demo.repository;

import com.example.demo.entity.ActivityMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActivityMasterRepository extends JpaRepository<ActivityMaster, Long> {
    Optional<ActivityMaster> findByActivityKey(String activityKey);
}
