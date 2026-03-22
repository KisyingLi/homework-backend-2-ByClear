package com.example.demo.repository;

import com.example.demo.model.UserLoginRecordModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserLoginRecordRepository extends JpaRepository<UserLoginRecordModel, Long> {
    List<UserLoginRecordModel> findByUserIdOrderByLoginDateDesc(Long userId);
    Optional<UserLoginRecordModel> findByUserIdAndLoginDate(Long userId, LocalDate loginDate);
}
