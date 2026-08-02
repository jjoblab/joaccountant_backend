package jo.accountant.demo.builders;

import java.util.UUID;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;

/**
 * Builder fluent pour créer des tiers démo (clients, fournisseurs, bailleurs, employés).
 
 *
 * @author jo@Dev


*/
public class ThirdPartyBuilder {

    private final ThirdParty tp = new ThirdParty();

    public ThirdPartyBuilder() {
        tp.setActive(true);
    }

    public ThirdPartyBuilder type(ThirdPartyType t) { tp.setType(t); return this; }
    public ThirdPartyBuilder name(String n) { tp.setName(n); return this; }
    public ThirdPartyBuilder collectiveAccountId(UUID id) { tp.setCollectiveAccountId(id); return this; }
    public ThirdPartyBuilder dedicatedAccountId(UUID id) { tp.setDedicatedAccountId(id); return this; }
    public ThirdPartyBuilder email(String e) { tp.setEmail(e); return this; }
    public ThirdPartyBuilder address(String a) { tp.setAddress(a); return this; }
    public ThirdPartyBuilder nif(String n) { tp.setNif(n); return this; }
    public ThirdPartyBuilder siret(String s) { tp.setSiret(s); return this; }
    public ThirdPartyBuilder vatNumber(String v) { tp.setVatNumber(v); return this; }
    public ThirdPartyBuilder active(boolean a) { tp.setActive(a); return this; }

    public ThirdParty build() {
        if (tp.getName() == null) throw new IllegalStateException("name required");
        if (tp.getType() == null) throw new IllegalStateException("type required");
        if (tp.getCollectiveAccountId() == null) throw new IllegalStateException("collectiveAccountId required");
        return tp;
    }
}
