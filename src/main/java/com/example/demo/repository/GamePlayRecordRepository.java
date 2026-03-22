package com.example.demo.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.demo.model.GamePlayRecordModel;

@Repository
public interface GamePlayRecordRepository extends JpaRepository<GamePlayRecordModel, Long> {
	List<GamePlayRecordModel> findByUserId(Long userId);
}
