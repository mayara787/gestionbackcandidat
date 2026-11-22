package com.psytech.proctoring.repository;

import com.psytech.proctoring.model.ProctoringSession;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<ProctoringSession, Long> {
    Optional<ProctoringSession> findByInvitationId(Long invitationId);
}
