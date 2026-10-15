package es.brasatech.fastbite.jpa.group;

import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.application.office.I18nConfig;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.jpa.i18n.Translations;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JPA implementation of GroupService. Default-language texts live in the entity's columns,
 * other languages in its translations column.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class GroupServiceJpaImpl implements GroupService {

    private final GroupJpaRepository repository;
    private final I18nConfig i18nConfig;

    @Override
    public List<Group> findAll() {
        String language = LocaleContextHolder.getLocale().getLanguage();
        return repository.findAll().stream().map(entity -> toGroup(entity, language)).toList();
    }

    @Override
    public Optional<Group> findById(String id) {
        return repository.findById(id).map(entity -> toGroup(entity, LocaleContextHolder.getLocale().getLanguage()));
    }

    @Override
    public Group create(Group group) {
        GroupEntity entity = new GroupEntity(null, group.name(), group.description(), group.icon(), productsOf(group.products()));
        return toGroup(repository.save(entity), i18nConfig.getDefaultLanguage());
    }

    @Override
    public Optional<Group> update(String id, Group group) {
        return repository.findById(id).map(entity -> {
            entity.setName(group.name());
            entity.setDescription(group.description());
            entity.setIcon(group.icon());
            entity.setProducts(productsOf(group.products()));
            return toGroup(repository.save(entity), i18nConfig.getDefaultLanguage());
        });
    }

    @Override
    public boolean delete(String id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    @Override
    public void clear() {
        repository.deleteAll();
    }

    private Group toGroup(GroupEntity entity, String language) {
        var translations = entity.getTranslations();
        return new Group(
                entity.getId(),
                Translations.get(translations, language, "name", entity.getName()),
                Translations.get(translations, language, "description", entity.getDescription()),
                entity.getIcon(),
                // A copy: the entity's own collection cannot be read once the transaction is over
                entity.getProducts() != null ? new ArrayList<>(entity.getProducts()) : null);
    }

    private static List<String> productsOf(List<String> products) {
        return products != null ? new ArrayList<>(products) : new ArrayList<>();
    }
}
