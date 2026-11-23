package es.brasatech.fastbite.controller;

import es.brasatech.fastbite.domain.exception.ResourceNotFoundException;
import es.brasatech.fastbite.application.office.GroupService;
import es.brasatech.fastbite.domain.group.Group;
import es.brasatech.fastbite.dto.office.BackOfficeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/api/backoffice/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;

    // ===== GROUP ENDPOINTS =====

    /**
     * Get all groups
     */
    @GetMapping
    @ResponseBody
    public ResponseEntity<List<BackOfficeDto<Group>>> getAllGroups() {
        List<Group> groups = groupService.findAll();
        List<BackOfficeDto<Group>> response = groups.stream()
                .map(group -> BackOfficeDto.of(group.id(), group))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * Get group by ID
     */
    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<Group>> getGroupById(@PathVariable String id) {
        return groupService.findById(id)
                .map(group -> BackOfficeDto.of(group.id(), group))
                .map(ResponseEntity::ok)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Create a new group
     */
    @PostMapping
    @ResponseBody
    public ResponseEntity<BackOfficeDto<Group>> createGroup(@RequestBody Group group) {
        Group created = groupService.create(group);
        BackOfficeDto<Group> response = BackOfficeDto.of(created.id(), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Update an existing group
     */
    @PutMapping("/{id}")
    @ResponseBody
    public ResponseEntity<BackOfficeDto<Group>> updateGroup(
            @PathVariable String id,
            @RequestBody Group group) {
        return groupService.update(id, group)
                .map(updated -> BackOfficeDto.of(updated.id(), updated))
                .map(ResponseEntity::ok)
                .orElseThrow(ResourceNotFoundException::new);
    }

    /**
     * Delete a group
     */
    @DeleteMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteGroup(@PathVariable String id) {
        if (groupService.delete(id)) {
            return ResponseEntity.ok().build();
        }
        throw new ResourceNotFoundException();
    }
}
