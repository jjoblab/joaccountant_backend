package jo.accountant.approvalworkflow.dto;

import jakarta.validation.constraints.Size;

/**
 * Corps de requête pour {@code POST .../requests/{id}/approve} et
 * {@code POST .../requests/{id}/reject}.
 *
 * @param comment motif de la décision (obligatoire pour rejet, optionnel pour approbation)
 
 *
 * @author jo@Dev


*/
public record DecisionRequest(@Size(max = 500) String comment) {}
