package de.intranda.goobi.plugins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Step;
import org.goobi.plugins.datatype.Config;
import org.goobi.plugins.datatype.MetadataMappingObject;
import org.goobi.plugins.datatype.Metadatum;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;

@PluginImplementation
@Log4j2
public class YerushaMetadataValidationPlugin implements IStepPluginVersion2 {

    @Getter
    private PluginGuiType pluginGuiType = PluginGuiType.PART;
    @Getter
    private PluginType type = PluginType.Step;

    @Getter
    private Step step;

    @Getter
    private String title = "intranda_step_metadata_yerusha_validation";

    @Getter
    private int interfaceVersion = 1;

    private Config config;

    @Getter
    private List<Metadatum> validationErrors;

    @Getter
    @Setter
    private String displayStatus = "up";

    @Override
    public void initialize(Step step, String returnPath) {
        this.step = step;
        runValidation();
    }

    private void runValidation() {
        validationErrors = new ArrayList<>();
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig("intranda_workflow_excelimport");
        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config");
        } catch (IllegalArgumentException e) {
            log.error("Unable to load config element from config file", e);
        }

        config = new Config(myconfig);
        try {
            // read mets file
            Fileformat ff = step.getProzess().readMetadataFile();
            Prefs prefs = step.getProzess().getRegelsatz().getPreferences();

            DocStruct logical = ff.getDigitalDocument().getLogicalDocStruct();
            DocStruct anchor = null;
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = logical.getAllChildren().get(0);
            }
            // validate each configured field against existing metadata

            List<Metadata> metatdaToValidate = new ArrayList<>();

            for (MetadataMappingObject mmo : config.getMetadataList()) {
                Metadata metadata = null;
                if (anchor != null && "anchor".equals(mmo.getDocType())) {
                    for (ugh.dl.Metadata md : anchor.getAllMetadata()) {
                        if (md.getType().getName().equals(mmo.getRulesetName())) {
                            metatdaToValidate.add(md);
                            metadata = md;
                        }
                    }
                } else {
                    for (ugh.dl.Metadata md : logical.getAllMetadata()) {
                        if (md.getType().getName().equals(mmo.getRulesetName())) {
                            metatdaToValidate.add(md);
                            metadata = md;
                        }
                    }
                }
                if (metadata == null) {
                    try {
                        metadata = new Metadata(prefs.getMetadataTypeByName(mmo.getRulesetName()));
                        metatdaToValidate.add(metadata);
                    } catch (MetadataTypeNotAllowedException e) {
                        log.error(e);
                    }
                }
                if (metadata != null) {
                    mmo.setHeaderName(metadata.getType().getLanguage("en"));
                } else {
                    mmo.setHeaderName(mmo.getIdentifier());
                }
            }

            for (MetadataMappingObject mmo : config.getMetadataList()) {
                List<MetadataMappingObject> mmoSubList = getSublistForMetadata(mmo.getRulesetName());
                int occurrence = 0;
                if (mmoSubList.size() > 1) {
                    occurrence = mmoSubList.indexOf(mmo) + 1;
                }
                List<Metadatum> metadatavalidationResults = validateMetadatum(metatdaToValidate, mmo, occurrence);
                for (Metadatum metadatum : metadatavalidationResults) {
                    if (!metadatum.isValid()) {
                        validationErrors.add(metadatum);
                    }
                }
            }

        } catch (ReadException | PreferencesException | IOException | SwapException e) {
            log.error(e);
        }

    }

    private List<Metadatum> validateMetadatum(List<Metadata> metatdaToValidate, MetadataMappingObject mmo, int occurrence) {
        List<Metadatum> validationResults = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int counter = 1;
        for (Metadata md : metatdaToValidate) {
            if (md.getType().getName().equals(mmo.getRulesetName())) {
                if (occurrence == 0 || counter == occurrence) {
                    values.add(md.getValue());
                }
                counter++;
            }
        }
        if (values.isEmpty()) {
            values.add("");
        }
        for (String value : values) {
            Metadatum datum = new Metadatum();
            datum.setHeadername(mmo.getHeaderName());
            validationResults.add(datum);
            if (value == null) {
                value = "";
            }
            value = value.replaceAll("¶", "<br/><br/>");
            value = value.replaceAll("\\u00A0|\\u2007|\\u202F", " ").trim();
            datum.setValue(value);
            // check if value is empty but required
            if (mmo.isRequired()) {
                if (value == null || value.isEmpty()) {
                    datum.setValid(false);
                    datum.getErrorMessages().add(mmo.getRequiredErrorMessage());
                }
            }
            // check if value matches the configured pattern
            if (mmo.getPattern() != null && value != null && !value.isEmpty()) {
                Pattern pattern = mmo.getPattern();
                Matcher matcher = pattern.matcher(value);
                if (!matcher.find()) {
                    datum.setValid(false);
                    datum.getErrorMessages().add(mmo.getPatternErrorMessage());
                }
            }
            // checks whether all parts of value are in the list of controlled contents
            if (!(mmo.getValidContent().isEmpty() || value == null || value.isEmpty())) {
                String[] valueList = value.split("; ");
                for (String v : valueList) {
                    if (!mmo.getValidContent().contains(v)) {
                        datum.setValid(false);
                        datum.getErrorMessages().add(mmo.getValidContentErrorMessage());
                    }
                }
            }

            // check if a configured requirement of either field having content is
            // fulfilled
            if (!mmo.getEitherHeader().isEmpty()) {
                Metadata eitherMetadata = null;
                for (MetadataMappingObject other : config.getMetadataList()) {
                    if (other.getIdentifier().equals(mmo.getEitherHeader())) {
                        for (Metadata md : metatdaToValidate) {
                            if (md.getType().getName().equals(other.getRulesetName())) {
                                eitherMetadata = md;
                                break;
                            }
                        }
                    }
                }

                if ((eitherMetadata == null || StringUtils.isBlank(eitherMetadata.getValue())) && value.isEmpty()) {
                    datum.setValid(false);
                    datum.getErrorMessages().add(mmo.getEitherErrorMessage());
                }
            }
            // check if field has content despite required field not having content
            if (!mmo.getRequiredHeaders()[0].isEmpty()) {
                for (String requiredHeader : mmo.getRequiredHeaders()) {
                    Metadata requiredMetadata = null;
                    for (MetadataMappingObject other : config.getMetadataList()) {
                        if (other.getIdentifier().equals(requiredHeader)) {
                            for (Metadata md : metatdaToValidate) {
                                if (md.getType().getName().equals(other.getRulesetName())) {
                                    requiredMetadata = md;
                                    break;
                                }
                            }
                        }
                    }

                    if ((requiredMetadata == null || StringUtils.isBlank(requiredMetadata.getValue())) && !value.isEmpty()) {
                        datum.setValid(false);
                        if (!datum.getErrorMessages().contains(mmo.getRequiredHeadersErrormessage())) {
                            datum.getErrorMessages().add(mmo.getRequiredHeadersErrormessage());
                        }
                    }
                }
            }
            //check if field has the demanded wordcount
            if (mmo.getWordcount() != 0) {
                String[] wordArray = value.split(" ");
                if (wordArray.length < mmo.getWordcount()) {
                    datum.setValid(false);
                    datum.getErrorMessages().add(mmo.getWordcountErrormessage());
                }
            }
        }
        return validationResults;
    }

    @Override
    public String cancel() {
        return "";
    }

    @Override
    public String finish() {
        return "";
    }

    @Override
    public boolean execute() {
        return false;
    }

    @Override
    public PluginReturnValue run() {
        return null;
    }

    @Override
    public String getPagePath() {
        return null;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    public void reload() {
        runValidation();
    }

    public static void main(String[] args) {
        String value = "tsdavo@archives.gov.ua ";
        value = value.replaceAll("\\u00A0|\\u2007|\\u202F", " ").trim();

        //      value = value.trim();
        //      value = value.replaceAll("\\u00A0","");
        System.out.println("-" + value + "-");
    }

    private List<MetadataMappingObject> getSublistForMetadata(String metadataname) {
        List<MetadataMappingObject> returnlist = new ArrayList<>();
        for (MetadataMappingObject mmo : config.getMetadataList()) {
            if (mmo.getRulesetName().equals(metadataname)) {
                returnlist.add(mmo);
            }
        }
        return returnlist;
    }
}
