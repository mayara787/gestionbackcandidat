package com.psytech.proctoring.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Une session de surveillance = un candidat invité (invitationId, le même que dans la base de Node). */
@Entity
@Table(name = "proctoring_session")
public class ProctoringSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long invitationId;

    private String ipDepart;
    private String paysDepart;
    private String dernierIp;
    private String dernierPays;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected ProctoringSession() {}

    public ProctoringSession(Long invitationId) {
        this.invitationId = invitationId;
    }

    public Long getId() { return id; }
    public Long getInvitationId() { return invitationId; }
    public String getIpDepart() { return ipDepart; }
    public String getPaysDepart() { return paysDepart; }
    public String getDernierIp() { return dernierIp; }
    public String getDernierPays() { return dernierPays; }
    public Instant getCreatedAt() { return createdAt; }

    public void setIpDepart(String ipDepart) { this.ipDepart = ipDepart; }
    public void setPaysDepart(String paysDepart) { this.paysDepart = paysDepart; }
    public void setDernierIp(String dernierIp) { this.dernierIp = dernierIp; }
    public void setDernierPays(String dernierPays) { this.dernierPays = dernierPays; }
}
