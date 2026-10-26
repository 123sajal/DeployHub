package com.deployhub.platform.repositories;

import com.deployhub.platform.models.entities.DatabaseServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DatabaseServerRepository extends JpaRepository<DatabaseServer, Long> {

    Optional<DatabaseServer> findByEngine(String engine);
}
