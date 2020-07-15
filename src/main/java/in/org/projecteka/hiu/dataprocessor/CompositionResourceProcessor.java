package in.org.projecteka.hiu.dataprocessor;

import in.org.projecteka.hiu.dataprocessor.model.BundleContext;
import in.org.projecteka.hiu.dataprocessor.model.DataContext;
import in.org.projecteka.hiu.dataprocessor.model.ProcessContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class CompositionResourceProcessor implements HITypeResourceProcessor {
    private static final Logger logger = LoggerFactory.getLogger(CompositionResourceProcessor.class);
    @Override
    public boolean supports(ResourceType type) {
        return type.equals(ResourceType.Composition);
    }

    @Override
    public void process(Resource resource, DataContext dataContext, BundleContext bundleContext, ProcessContext processContext) {
        Composition composition = (Composition) resource;
        List<Composition.SectionComponent> sections = composition.getSection();
        ProcessContext compositionContext = new ProcessContext(composition::getDate, composition::getId, composition::getResourceType);
        for (Composition.SectionComponent section : sections) {
            section.getEntry().forEach(entry -> {
                processCompositionEntry(entry, dataContext, bundleContext, compositionContext);
            });
        }
        String title = String.format("%s : %s", composition.getTitle(), FHIRUtils.getDisplay(composition.getType()));
        bundleContext.trackResource(ResourceType.Composition, resource.getId(), ((Composition) resource).getDate(), title);
    }


    private void processCompositionEntry(Reference entry, DataContext dataContext, BundleContext bundleContext, ProcessContext compositionContext) {
        IBaseResource entryResource = entry.getResource();
        if (entryResource == null) {
            compositionContext.getContextResourceId();
            logger.warn(String.format("Composition section entry not found. Composition id: %s, Entry reference: %s",
                    compositionContext.getContextResourceId(), entry.getReference()));
        }
        if (!(entryResource instanceof Resource)) {
            return;
        }
        Resource bundleResource = (Resource) entryResource;
        HITypeResourceProcessor resProcessor = bundleContext.findResourceProcessor(bundleResource.getResourceType());
        if (resProcessor != null) {
            resProcessor.process(bundleResource, dataContext, bundleContext, compositionContext);
        }
    }
}
