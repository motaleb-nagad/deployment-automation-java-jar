package com.nagad.deploystg.repo;

import com.nagad.deploystg.domain.Deployment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeploymentRepository extends JpaRepository<Deployment, String> {
}
