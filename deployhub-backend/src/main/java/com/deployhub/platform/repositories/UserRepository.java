package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.UserMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserMaster, Long> {

    Optional<UserMaster> findByEmail(String email);
}
