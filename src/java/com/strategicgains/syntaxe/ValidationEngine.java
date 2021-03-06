/*
    Copyright 2010, Strategic Gains, Inc.

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
 */
package com.strategicgains.syntaxe;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.strategicgains.syntaxe.annotation.FieldValidation;
import com.strategicgains.syntaxe.annotation.ObjectValidation;
import com.strategicgains.syntaxe.annotation.ValidationProvider;
import com.strategicgains.syntaxe.annotation.encoding.EncodingProvider;
import com.strategicgains.syntaxe.encoding.XssEncoder;
import com.strategicgains.syntaxe.util.ClassUtils;
import com.strategicgains.syntaxe.validator.AnnotatedFieldValidator;
import com.strategicgains.syntaxe.validator.Validator;

/**
 * @author toddf
 * @since Oct 7, 2010
 */
public class ValidationEngine
{
	private static final ConcurrentHashMap<Class<?>, List<Field>> cachedFieldsByClass = new ConcurrentHashMap<Class<?>, List<Field>>();
	private static final ConcurrentHashMap<Integer, List<Validator>> cachedValidatorsByHashcode = new ConcurrentHashMap<Integer, List<Validator>>();
	private static final ConcurrentHashMap<Class<?>, Validator> cachedObjectValidatorsByClass = new ConcurrentHashMap<Class<?>, Validator>();
	private static final ConcurrentHashMap<Integer, List<XssEncoder>> cachedEncodersByHashcode = new ConcurrentHashMap<Integer, List<XssEncoder>>();
	private static final ThreadLocal<Set<Object>> visitedObjects = new ThreadLocal<Set<Object>>();

	private ValidationEngine()
	{
		// Prevents instantiation.
	}

	/**
	 * Validates the object, returning a list of error messages
	 * if validation fails.  If the list is empty, no errors
	 * occurred.
	 * <p/>
	 * Also calls encode() to leverage any XSS encoding annotations.
	 * <p/>
	 * Note: this method calls Validatable.validate() if the
	 * instance being validated implements the Validatable interface.
	 * However, if Validatable.validate() turns around and calls
	 * the ValidationEngine (with the same object), you will get
	 * inconsistent results--with more-than the expected number of
	 * error messages.
	 * 
	 * @param object
	 * @return a List of error message strings. Never null.
	 */
	public static List<String> validate(Object object)
	{
		List<String> errors = new ArrayList<String>();

		if (visit(object)) return errors;

		encode(object);

		try
		{
			if (isValidatable(object))
			{
				try
				{
					callValidate((Validatable) object);
				}
				catch (ValidationException ve)
				{
					errors.addAll(ve.getErrors());
				}
			}

			validateFields(object, errors);
			validateObject(object, errors);
		}
		catch (Exception e)
		{
			errors.add("Exception while validating: " + e.getMessage());
		}
		finally
		{
            if (isRootValidationObject(object))
            {
                // don't clear the visited set until the whole graph has been validated and it's back to the root
                clearVisited();
            }
		}

		return errors;
	}

	/**
	 * Validates the object, throwing a ValidationException
	 * if there were errors.
	 * <p/>
	 * Also calls encode() to leverage any XSS encoding annotations.
	 * <p/>
	 * Note: that this method calls Validatable.validate() if the
	 * instance being validated implements the Validatable interface.
	 * However, if Validatable.validate() turns around and calls
	 * the ValidationEngine (on the same object), you will get
	 * inconsistent results--with more-than the expected number of
	 * error messages.
	 * 
	 * @param object
	 * @throws ValidationException containing the error messages if a validation error occurs.
	 */
	public static void validateAndThrow(Object object)
	{
		List<String> errors = validate(object);
		
		if (!errors.isEmpty())
		{
			throw new ValidationException(errors);
		}
	}

	/**
	 * Leverages the XSS-encoding annotations to encode the String fields of the object
	 * to help prevent cross-site scripting attacks.
	 * <p/>
	 * Note that validate() calls encode(), so it is not necessary to call both. This
	 * method is here in case you don't want to call validate() and only want to perform
	 * XSS encoding.
	 * 
	 * @param object an object in which to encode the fields for XSS prevention.
	 */
	public static void encode(Object object)
	{
		try
		{
			Collection<Field> fields = getAllDeclaredFields(object.getClass());

			for (Field field : fields)
			{
				List<XssEncoder> encoders = getEncoders(field, object);

				if (!encoders.isEmpty())
				{
					for (XssEncoder encoder : encoders)
					{
						field.setAccessible(true);
						String encoded = encoder.encode((String) field.get(object));
						field.set(object, encoded);
					}
				}
			}
		}
		catch (Exception e)
		{
			throw new EncodingException(e);
		}
	}

	private static void validateFields(Object object, List<String> errors)
	throws Exception
	{
		Collection<Field> fields = getAllDeclaredFields(object.getClass());

		for (Field field : fields)
		{
			List<Validator> validators = getValidators(field, object);

			if (!validators.isEmpty())
			{
				try
				{
					for (Validator validator : validators)
					{
						validator.perform(object, errors);
					}
				}
				catch (ValidationException e)
				{
					e.printStackTrace();
				}
			}
		}
	}

	private static List<Validator> getValidators(Field field, Object object)
	throws InstantiationException, IllegalAccessException, SecurityException,
		NoSuchMethodException, IllegalArgumentException, InvocationTargetException
	{
		List<Validator> validators = cachedValidatorsByHashcode.get(field.hashCode());
		
		if (validators != null)
		{
			return validators;
		}
		
		validators  = new ArrayList<Validator>();

		for (Annotation a : field.getAnnotations())
		{
            Validator<?> validator = null;
            Class<? extends Validator> validatorClass = null;

            // find a validator implementation. it can either be in a ValidationProvider annotation on the field's annotation,
            // or it can be in the value parameter of the field's FieldValidation annotation
			if (a.annotationType().isAnnotationPresent(ValidationProvider.class))
			{
				ValidationProvider providerAnnotation = a.annotationType().getAnnotation(ValidationProvider.class);
                validatorClass = providerAnnotation.value();
			}
			else if (a.annotationType().isAssignableFrom(FieldValidation.class))
            {
                validatorClass = ((FieldValidation) a).value();
            }

            if (validatorClass != null)
            {
                // if the validator extends AnnotatedFieldValidator use the (Field, Annotation) constructor, otherwise use the default constructor
                if (AnnotatedFieldValidator.class.isAssignableFrom(validatorClass))
                {
                    Constructor<? extends Validator> constructor = validatorClass.getConstructor(Field.class, a.annotationType());

                    if (constructor != null)
                    {
                        validator = constructor.newInstance(field, a);
                    }
                }
                else
                {
                    validator = validatorClass.newInstance();
                }

                // if a validator was found and instantiated, add it to the list
                if (validator != null)
                {
                    validators.add(validator);
                }
            }
		}

		cachedValidatorsByHashcode.put(field.hashCode(), validators);
		return validators;
	}

	private static List<XssEncoder> getEncoders(Field field, Object object)
	throws InstantiationException, IllegalAccessException, SecurityException,
		NoSuchMethodException, IllegalArgumentException, InvocationTargetException
	{
		List<XssEncoder> encoders = cachedEncodersByHashcode.get(field.hashCode());
		
		if (encoders != null)
		{
			return encoders;
		}
		
		encoders  = new ArrayList<XssEncoder>();

		for (Annotation a : field.getAnnotations())
		{
			if (a.annotationType().isAnnotationPresent(EncodingProvider.class))
			{
				EncodingProvider providerAnnotation = a.annotationType().getAnnotation(EncodingProvider.class);
				Constructor<?> constructor = providerAnnotation.value().getConstructor();
				XssEncoder provider = (XssEncoder) constructor.newInstance();
				encoders.add(provider);
			}
			else if (a.annotationType().isAssignableFrom(EncodingProvider.class))
			{
				Class<? extends XssEncoder> vc = ((EncodingProvider) a).value();
				encoders.add(vc.newInstance());
			}
		}

		cachedEncodersByHashcode.put(field.hashCode(), encoders);
		return encoders;
	}
	
	private static Collection<Field> getAllDeclaredFields(Class<?> aClass)
	{
		List<Field> fields = cachedFieldsByClass.get(aClass);
		
		if (fields == null)
		{
			fields = ClassUtils.getAllDeclaredFields(aClass);
			cachedFieldsByClass.put(aClass, fields);
		}
		
		return fields;
	}

	private static void validateObject(Object object, List<String> errors)
	throws InstantiationException, IllegalAccessException
	{
		ObjectValidation annotation = object.getClass().getAnnotation(ObjectValidation.class);
		
		if (annotation == null) return;
		
		Validator validator = cachedObjectValidatorsByClass.get(object.getClass());
		
		if (validator == null)
		{
			validator = annotation.value().newInstance();
			cachedObjectValidatorsByClass.put(object.getClass(), validator);
		}
		
		validator.perform(object, errors);
	}

	private static boolean isValidatable(Object object)
	{
		return (Validatable.class.isAssignableFrom(object.getClass()));
	}
	
	private static void callValidate(Validatable object)
	{
		object.validate();
	}

	/**
	 * Returns true if the object has been visited by the validation engine.
	 * Otherwise, marks the object as visited and returns false.
	 * 
	 * @param object an object to visit
	 * @return true if the object has already been visited. Otherwise, false.
	 */
	private static boolean visit(Object object)
	{
		Set<Object> s = visitedObjects.get();

		if (s != null)
		{
			if (s.contains(object)) return true;
		}

		markVisited(object);
		return false;
	}

	private static void markVisited(Object object)
	{
		Set<Object> s = visitedObjects.get();
		
		if (s == null)
		{
			s = new LinkedHashSet<Object>(); // use an ordered set to know the first object is the root
			visitedObjects.set(s);
		}

		s.add(object);
	}

	private static void clearVisited()
	{
		Set<Object> s = visitedObjects.get();

		if (s != null)
		{
			s.clear();
			visitedObjects.remove();
		}
	}

    private static boolean isRootValidationObject(Object object)
    {
        Set<Object> s = visitedObjects.get();

        if (s != null)
        {
            if (s.iterator().hasNext())
            {
                return (s.iterator().next() == object);
            }
        }

        return false;
    }
}
