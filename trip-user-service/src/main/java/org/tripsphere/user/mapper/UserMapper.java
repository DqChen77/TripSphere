package org.tripsphere.user.mapper;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;
import org.tripsphere.user.model.Role;
import org.tripsphere.user.model.UserEntity;
import org.tripsphere.user.v1.User;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    @Mapping(target = "roles", source = "roles", qualifiedByName = "rolesToStringList")
    User toProto(UserEntity userEntity);

    List<User> toProtoList(List<UserEntity> userEntities);

    @Named("rolesToStringList")
    default List<String> rolesToStringList(Set<Role> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream().map(Role::name).collect(Collectors.toList());
    }

    @Named("stringListToRoles")
    default Set<Role> stringListToRoles(List<String> roleNames) {
        if (roleNames == null) {
            return Set.of();
        }
        return roleNames.stream().map(Role::valueOf).collect(Collectors.toSet());
    }
}
