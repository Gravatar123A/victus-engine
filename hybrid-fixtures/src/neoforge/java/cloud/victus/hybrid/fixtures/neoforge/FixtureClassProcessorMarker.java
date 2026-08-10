// SPDX-License-Identifier: GPL-3.0-only
package cloud.victus.hybrid.fixtures.neoforge;

import net.neoforged.neoforgespi.transformation.ClassProcessor;
import net.neoforged.neoforgespi.transformation.ClassProcessorProvider;
import net.neoforged.neoforgespi.transformation.ProcessorName;

/** Service-loaded no-op FML class processor used to prove transformation-pipeline assembly. */
public final class FixtureClassProcessorMarker implements ClassProcessorProvider {
    public static final String MARKER = "VICTUS_FIXTURE_NEOFORGE_CLASS_PROCESSOR";

    @Override
    public void createProcessors(Context context, Collector collector) {
        collector.add(new MarkerProcessor());
        FixtureSignals.marker("class_processor_discovered", MARKER + "_DISCOVERED");
    }

    private static final class MarkerProcessor implements ClassProcessor {
        private static final ProcessorName NAME = new ProcessorName("victus_fixture", "noop");

        @Override
        public ProcessorName name() {
            return NAME;
        }

        @Override
        public boolean handlesClass(SelectionContext context) {
            boolean markerClass = context.type().getClassName().equals(FixtureNeoForgeMod.class.getName());
            if (markerClass) {
                FixtureSignals.marker("class_processor_selected", MARKER + "_SELECTED");
            }
            return markerClass;
        }

        @Override
        public ComputeFlags processClass(TransformationContext context) {
            FixtureSignals.marker("class_processor", MARKER);
            context.audit(MARKER);
            return ComputeFlags.NO_REWRITE;
        }
    }
}
