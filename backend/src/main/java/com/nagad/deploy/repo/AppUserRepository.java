package com.nagad.deploy.repo;

import com.nagad.deploy.domain.AppUser;
import com.nagad.deploy.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
    List<AppUser> findByRole(Role role);
}
