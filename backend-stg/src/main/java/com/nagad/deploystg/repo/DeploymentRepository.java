package com.nagad.deploystg.repo;

import com.nagad.deploystg.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {
    /** Most-recent staging runs (this service only ever writes stg-* rows). */
    List<Deployment> findTop200ByGroupNameStartingWithOrderByStartedAtDesc(String groupPrefix);
}
