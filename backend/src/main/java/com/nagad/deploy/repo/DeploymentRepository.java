package com.nagad.deploy.repo;

import com.nagad.deploy.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {
    List<Deployment> findAllByOrderByStartedAtDesc();
}
