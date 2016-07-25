package com.hurence.logisland.processor.parser;

import com.hurence.logisland.components.PropertyDescriptor;
import com.hurence.logisland.event.Event;
import com.hurence.logisland.log.AbstractLogParser;
import com.hurence.logisland.log.LogParserException;
import com.hurence.logisland.processor.ProcessContext;
import com.hurence.logisland.validators.StandardValidators;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by tom on 21/07/16.
 */
public class SplitText extends AbstractLogParser {

    private static Logger logger = LoggerFactory.getLogger(SplitText.class);


    public static final PropertyDescriptor VALUE_REGEX = new PropertyDescriptor.Builder()
            .name("value.regex")
            .description("the regex to match for the message value")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor VALUE_FIELDS = new PropertyDescriptor.Builder()
            .name("value.fields")
            .description("a comma separated list of fields corresponding to matching groups for the message value")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor KEY_REGEX = new PropertyDescriptor.Builder()
            .name("key.regex")
            .description("the regex to match for the message key")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue(".*")
            .build();

    public static final PropertyDescriptor KEY_FIELDS = new PropertyDescriptor.Builder()
            .name("key.fields")
            .description("a comma separated list of fields corresponding to matching groups for the message key")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .defaultValue("")
            .build();

    public static final PropertyDescriptor EVENT_TYPE = new PropertyDescriptor.Builder()
            .name("event.type")
            .description("the type of event")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(ERROR_TOPICS);
        descriptors.add(INPUT_TOPICS);
        descriptors.add(OUTPUT_TOPICS);
        descriptors.add(OUTPUT_SCHEMA);
        descriptors.add(VALUE_REGEX);
        descriptors.add(VALUE_FIELDS);
        descriptors.add(KEY_REGEX);
        descriptors.add(KEY_FIELDS);
        descriptors.add(EVENT_TYPE);

        return Collections.unmodifiableList(descriptors);
    }

    @Override
    public Collection<Event> parse(ProcessContext context, String key, String value) throws LogParserException {

        final String[] keyFields = context.getProperty(KEY_FIELDS).getValue().split(",");
        final String keyRegexString = context.getProperty(KEY_REGEX).getValue();
        final Pattern keyRegex = Pattern.compile(keyRegexString);
        final String[] valueFields = context.getProperty(VALUE_FIELDS).getValue().split(",");
        final String valueRegexString = context.getProperty(VALUE_REGEX).getValue();
        final String eventType = context.getProperty(EVENT_TYPE).getValue();
        final Pattern valueRegex = Pattern.compile(valueRegexString);

        List<Event> events = new ArrayList<>();

        /**
         * try to match the regexp to create an event
         */
        try {
            Event event = new Event(eventType);

            // match the key
            Matcher keyMatcher = keyRegex.matcher(key);
            if (keyMatcher.matches()) {
                for (int i = 0; i < keyMatcher.groupCount() + 1 && i < keyFields.length; i++) {
                    String content = keyMatcher.group(i);
                    if (content != null) {
                        event.put(keyFields[i], "string", keyMatcher.group(i+1).replaceAll("\"", ""));

                    }
                }
            }

            // match the value
            Matcher valueMatcher = valueRegex.matcher(value);
            if (valueMatcher.lookingAt()) {
                for (int i = 0; i < valueMatcher.groupCount() + 1 && i < valueFields.length; i++) {
                    String content = valueMatcher.group(i);
                    if (content != null) {
                        event.put(valueFields[i], "string", valueMatcher.group(i).replaceAll("\"", ""));
                    }
                }
                events.add(event);
            } else {
                logger.warn("no match");
            }

        } catch (Exception e) {
            logger.warn("issue while matching regex {} on string {} exception {}", valueRegexString, value, e.getMessage());
        }


        return events;
    }

    @Override
    public String getIdentifier() {
        return null;
    }


}