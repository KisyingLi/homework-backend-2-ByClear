package com.example.demo.repository;

import com.example.demo.model.GamePlayRecordModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GamePlayRecordRepository extends JpaRepository<GamePlayRecordModel, Long> {
}
