package org.tripsphere.user.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.tripsphere.user.model.Role;
import org.tripsphere.user.model.UserEntity;
import org.tripsphere.user.v1.User;

class UserMapperTest {

    private final UserMapper mapper = UserMapper.INSTANCE;

    @Test
    void toProto_shouldMapBasicFields() {
        UserEntity entity = new UserEntity();
        entity.setId("user-123");
        entity.setUsername("testuser");
        entity.setPassword("secret");
        entity.setRoles(Set.of(Role.USER));

        User proto = mapper.toProto(entity);

        assertThat(proto.getId()).isEqualTo("user-123");
        assertThat(proto.getUsername()).isEqualTo("testuser");
    }

    @Test
    void toProto_shouldMapRolesToStringList() {
        UserEntity entity = new UserEntity();
        entity.setId("user-456");
        entity.setUsername("admin");
        entity.setPassword("secret");
        entity.setRoles(Set.of(Role.USER, Role.ADMIN));

        User proto = mapper.toProto(entity);

        assertThat(proto.getRolesList()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void toProto_shouldHandleEmptyRoles() {
        UserEntity entity = new UserEntity();
        entity.setId("user-789");
        entity.setUsername("noroles");
        entity.setPassword("secret");
        entity.setRoles(Set.of());

        User proto = mapper.toProto(entity);

        assertThat(proto.getRolesList()).isEmpty();
    }

    @Test
    void toProto_shouldHandleNullRoles() {
        UserEntity entity = new UserEntity();
        entity.setId("user-000");
        entity.setUsername("nullroles");
        entity.setPassword("secret");
        entity.setRoles(null);

        User proto = mapper.toProto(entity);

        assertThat(proto.getRolesList()).isEmpty();
    }

    @Test
    void toProtoList_shouldMapMultipleEntities() {
        UserEntity user1 = new UserEntity("id1", "user1", "pass1", Set.of(Role.USER));
        UserEntity user2 = new UserEntity("id2", "user2", "pass2", Set.of(Role.ADMIN));

        List<User> protos = mapper.toProtoList(List.of(user1, user2));

        assertThat(protos).hasSize(2);
        assertThat(protos.get(0).getUsername()).isEqualTo("user1");
        assertThat(protos.get(1).getUsername()).isEqualTo("user2");
    }
}
