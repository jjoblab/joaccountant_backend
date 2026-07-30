package jo.accountant.core.framework;

/** Mode de numérotation des comptes imposé (ou non) par le référentiel comptable (§4). */
public enum NumberingMode {
    /** IFRS full / IFRS PME — aucune structure de classes imposée. */
    FREE,
    /** SYSCOHADA, PCG, PCN, PCGR — classes 1-8/9 fixées par la réglementation. */
    MANDATED
}
