package org.tripsphere.user.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tripsphere.user.model.UserEntity;

public interface UserEntityRepository extends JpaRepository<UserEntity, String> {
    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
