package es.brasatech.fastbite.jpa.user;

import es.brasatech.fastbite.application.office.UserService;
import es.brasatech.fastbite.domain.user.UserDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceJpaImpl implements UserService {

    private final UserJpaRepository userRepository;

    @Override
    public Optional<UserDto> findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toDto);
    }

    @Override
    @Transactional
    public void save(UserDto userDto) {
        UserEntity entity = userRepository.findByUsername(userDto.username())
                .orElse(new UserEntity());

        entity.setUsername(userDto.username());
        entity.setPassword(userDto.password());
        entity.setFullName(userDto.fullName());
        entity.setRoles(userDto.roles() != null ? new java.util.HashSet<>(userDto.roles()) : new java.util.HashSet<>());
        entity.setActive(userDto.active());
        entity.setTenantId(userDto.tenantId());

        userRepository.save(entity);
    }

    @Override
    public boolean existsAny() {
        return userRepository.count() > 0;
    }

    @Override
    public java.util.List<UserDto> findAll() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    public void delete(String id) {
        userRepository.deleteById(id);
    }

    private UserDto toDto(UserEntity entity) {
        return new UserDto(
                entity.getId(),
                entity.getUsername(),
                entity.getPassword(),
                entity.getFullName(),
                entity.getRoles(),
                entity.isActive(),
                entity.getTenantId());
    }
}
