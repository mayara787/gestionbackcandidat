package com.psytech.proctoring.repository;

import com.psytech.proctoring.model.EventType;
import com.psytech.proctoring.model.ProctorEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<ProctorEvent, Long> {

    List<ProctorEvent> findByInvitationIdOrderByOccurredAtAscIdAsc(Long invitationId);

    /** Temps de première réponse des AUTRES candidats sur une question (base de comparaison statistique). */
    @Query("""
            select e.tempsMs from ProctorEvent e
            where e.type = :type and e.questionId = :questionId
              and e.invitationId <> :invitationId and e.tempsMs is not null
            """)
    List<Long> tempsDesAutres(@Param("type") EventType type,
                              @Param("questionId") Long questionId,
                              @Param("invitationId") Long invitationId);
}
