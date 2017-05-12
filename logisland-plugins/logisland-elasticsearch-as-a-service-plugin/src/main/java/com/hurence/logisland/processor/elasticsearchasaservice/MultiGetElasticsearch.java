package com.hurence.logisland.processor.elasticsearchasaservice;


import com.hurence.logisland.annotation.documentation.CapabilityDescription;
import com.hurence.logisland.annotation.documentation.Tags;
import com.hurence.logisland.component.PropertyDescriptor;
import com.hurence.logisland.processor.ProcessContext;
import com.hurence.logisland.processor.ProcessError;
import com.hurence.logisland.processor.elasticsearchasaservice.multiGet.InvalidMultiGetQueryRecordException;
import com.hurence.logisland.processor.elasticsearchasaservice.multiGet.MultiGetQueryRecord;
import com.hurence.logisland.processor.elasticsearchasaservice.multiGet.MultiGetResponseRecord;
import com.hurence.logisland.record.*;
import com.hurence.logisland.validator.StandardValidators;

import java.util.*;

@Tags({"elasticsearch"})
@CapabilityDescription("Retrieves a content indexed in elasticsearch using elasticsearch multiget queries.\n" +
        "Each incoming record contains information regarding the elasticsearch multiget query that will be performed. This information is stored in record fields whose names are configured in the plugin properties (see below) :\n" +
        "- index (String) : name of the elasticsearch index on which the multiget query will be performed. This field is mandatory and should not be empty, otherwise an error output record is sent for this specific incoming record.\n" +
        "- type (String) : name of the elasticsearch type on which the multiget query will be performed. This field is not mandatory.\n" +
        "- ids (ArrayList<String>) : list of document ids to fetch. This field is mandatory and should not be empty, otherwise an error output record is sent for this specific incoming record.\n" +
        "- includes (ArrayList<String>) : list of patterns to filter in (include) fields to retrieve. Supports wildcards. This field is not mandatory.\n" +
        "- excludes (ArrayList<String>) : list of patterns to filter out (exclude) fields to retrieve. Supports wildcards. This field is not mandatory.\n" +
        "\n" +
        "Each outcoming record holds data of one elasticsearch retrieved document. This data is stored in these fields :\n" +
        "- index (same field name as the incoming record) : name of the elasticsearch index.\n" +
        "- type (same field name as the incoming record) : name of the elasticsearch type.\n" +
        "- id (same field name as the incoming record) : retrieved document id.\n" +
        "- a list of String fields containing :\n" +
        "   * field name : the retrieved field name\n" +
        "   * field value : the retrieved field value"
)
public class MultiGetElasticsearch extends AbstractElasticsearchProcessor
{

    public static final PropertyDescriptor ES_INDEX_FIELD = new PropertyDescriptor.Builder()
            .name("es.index.field")
            .description("the name of the incoming records field containing es index name to use in multiget query. ")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ES_TYPE_FIELD = new PropertyDescriptor.Builder()
            .name("es.type.field")
            .description("the name of the incoming records field containing es type name to use in multiget query")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ES_IDS_FIELD = new PropertyDescriptor.Builder()
            .name("es.ids.field")
            .description("the name of the incoming records field containing es document Ids to use in multiget query")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ES_INCLUDES_FIELD = new PropertyDescriptor.Builder()
            .name("es.includes.field")
            .description("the name of the incoming records field containing es includes to use in multiget query")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor ES_EXCLUDES_FIELD = new PropertyDescriptor.Builder()
            .name("es.excludes.field")
            .description("the name of the incoming records field containing es excludes to use in multiget query")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {

        List<PropertyDescriptor> props = new ArrayList<>();
        props.add(ELASTICSEARCH_CLIENT_SERVICE);
        props.add(ES_INDEX_FIELD);
        props.add(ES_TYPE_FIELD);
        props.add(ES_IDS_FIELD);
        props.add(ES_INCLUDES_FIELD);
        props.add(ES_EXCLUDES_FIELD);

        return Collections.unmodifiableList(props);
    }

    /**
     * process events
     *
     * @param context
     * @param records
     * @return
     */
    @Override
    public Collection<Record> process(final ProcessContext context, final Collection<Record> records) {

        List<Record> outputRecords = new ArrayList<>();

        if (records.size() != 0) {

            String indexFieldName = context.getPropertyValue(ES_INDEX_FIELD).asString();
            String typeFieldName = context.getPropertyValue(ES_TYPE_FIELD).asString();
            String idsFieldName = context.getPropertyValue(ES_IDS_FIELD).asString();
            String includesFieldName = context.getPropertyValue(ES_INCLUDES_FIELD).asString();
            String excludesFieldName = context.getPropertyValue(ES_EXCLUDES_FIELD).asString();

            List<MultiGetQueryRecord> multiGetQueryRecords = new ArrayList<>();
            List<MultiGetResponseRecord> multiGetResponseRecords = new ArrayList<>();

            for (Record record : records) {

                if(!record.hasField(indexFieldName) || record.getField(indexFieldName) == null || !record.getField(indexFieldName).getType().equals(FieldType.STRING) || record.getField(indexFieldName).getRawValue() == null || record.getField(indexFieldName).getRawValue().toString().isEmpty() ) {
                    StandardRecord outputRecord = new StandardRecord(record);
                    outputRecord.addError(ProcessError.BAD_RECORD.getName(), "record must have the field " + indexFieldName + " containing the index name to use in the multiget query. This field must be of STRING type and cannot be empty.");
                    outputRecords.add(outputRecord);
                    continue;
                }

                if(!record.hasField(idsFieldName) || record.getField(idsFieldName) == null || !record.getField(idsFieldName).getType().equals(FieldType.ARRAY) || record.getField(idsFieldName).getRawValue() == null || record.getField(idsFieldName).getRawValue().toString().isEmpty() ) {
                    StandardRecord outputRecord = new StandardRecord(record);
                    outputRecord.addError(ProcessError.BAD_RECORD.getName(), "record must have the field " + idsFieldName + " containing the Ids to use in the multiget query. This field must be of ARRAY type and cannot be empty.");
                    outputRecords.add(outputRecord);
                    continue;
                }

                String index = record.getField(indexFieldName).asString();
                String type = record.getField(typeFieldName) != null ? record.getField(typeFieldName).asString() : null;
                List<String> ids = new ArrayList<>((List<String>)record.getField(idsFieldName).getRawValue());
                List<String> includesList;
                String[] includesArray;
                if(record.getField(includesFieldName) != null && record.getField(includesFieldName).getRawValue() != null)
                {
                    includesList = new ArrayList<>((List<String>)record.getField(includesFieldName).getRawValue());
                    includesArray = new String[includesList.size()];
                    includesArray = includesList.toArray(includesArray);
                } else {
                    includesArray = null;
                }

                List<String> excludesList;
                String[] excludesArray;
                if(record.getField(excludesFieldName) != null && record.getField(excludesFieldName).getRawValue() != null)
                {
                    excludesList = new ArrayList<>((List<String>)record.getField(excludesFieldName).getRawValue());
                    excludesArray = new String[excludesList.size()];
                    excludesArray = excludesList.toArray(excludesArray);
                } else {
                    excludesArray = null;
                }

                try {
                    multiGetQueryRecords.add(new MultiGetQueryRecord(index, type, ids, includesArray, excludesArray));
                } catch (InvalidMultiGetQueryRecordException e) {
                    StandardRecord outputRecord = new StandardRecord(record);
                    outputRecord.addError(ProcessError.BAD_RECORD.getName(), e.getMessage());
                    outputRecords.add(outputRecord);
                    continue;
                }
            }

            multiGetResponseRecords = elasticsearchClientService.multiGet(multiGetQueryRecords);

            multiGetResponseRecords.forEach(responseRecord -> {
                StandardRecord outputRecord = new StandardRecord();
                outputRecord.setStringField(indexFieldName, responseRecord.getIndexName());
                outputRecord.setStringField(typeFieldName, responseRecord.getTypeName());
                outputRecord.setStringField(idsFieldName, responseRecord.getDocumentId());
                if(responseRecord.getRetrievedFields() != null)
                {
                    responseRecord.getRetrievedFields().forEach((k,v) -> {
                        outputRecord.setStringField(k.toString(), v.toString());
                    });
                }
                outputRecords.add(outputRecord);
            });
        }
        return outputRecords;
    }
}
