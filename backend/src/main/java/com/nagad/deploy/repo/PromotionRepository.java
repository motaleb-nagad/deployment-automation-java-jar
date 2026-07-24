package com.nagad.deploy.repo;

import com.nagad.deploy.domain.Promotion;
import com.nagad.deploy.domain.PromotionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, String> {
    List<Promotion> findByStatusOrderByRequestedAtDesc(PromotionStatus status);
    List<Promotion> findByRequestedByOrderByRequestedAtDesc(String requestedBy);
    List<Promotion> findAllByOrderByRequestedAtDesc();
    long countByStatus(PromotionStatus status);

    Optional<Promotion> findFirstByGroupNameAndAppAndStatusOrderByDecidedAtDesc(
            String groupName, String app, PromotionStatus status);
}
