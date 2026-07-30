package jo.accountant.auth.entity;

/** Rôles par (utilisateur, société) — §3.4. */
public enum UserRole {
    OWNER,
    ADMIN,
    ACCOUNTANT,
    BOOKKEEPER,
    VIEWER,
    AUDITOR
}
