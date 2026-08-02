package jo.accountant.app.dev;

import jo.accountant.app.JoAccountantApplication;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wrapper Spring Boot pour le mode dev — hérite de {@link JoAccountantApplication}
 * mais vit dans le package {@code jo.accountant.app.dev} pour éviter les conflits
 * de scan de composants avec le lanceur principal.
 *
 * <p>Lancé par {@link DevLauncher#main(String[])}.
 
 *
 * @author jo@Dev


*/
@SpringBootApplication(scanBasePackages = "jo.accountant")
public class JoAccountantDevApp {
    // Hérite de la config principale via scanBasePackages.
}
