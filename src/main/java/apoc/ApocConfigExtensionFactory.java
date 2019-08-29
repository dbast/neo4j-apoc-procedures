package apoc;

import org.neo4j.configuration.Config;
import org.neo4j.kernel.extension.ExtensionFactory;
import org.neo4j.kernel.extension.ExtensionType;
import org.neo4j.kernel.extension.context.ExtensionContext;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.internal.LogService;
import org.neo4j.procedure.impl.GlobalProceduresRegistry;

/**
 * a kernel extension for the new apoc configuration mechanism
 */
public class ApocConfigExtensionFactory extends ExtensionFactory<ApocConfigExtensionFactory.Dependencies> {

    public interface Dependencies {
        LogService log();
        Config config();
        GlobalProceduresRegistry globalProceduresRegistry();
    }

    public ApocConfigExtensionFactory() {
        super(ExtensionType.GLOBAL, "ApocConfig");
    }

    @Override
    public Lifecycle newInstance(ExtensionContext context, Dependencies dependencies) {
        return new ApocConfig(dependencies.config(), dependencies.log(), dependencies.globalProceduresRegistry());
    }

}
