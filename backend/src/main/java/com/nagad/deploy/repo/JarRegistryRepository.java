package com.nagad.deploy.repo;

import com.nagad.deploy.domain.JarRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JarRegistryRepository extends JpaRepository<JarRegistry, JarRegistry.Key> {
    List<JarRegistry> findAllByOrderByAppAsc();
}
