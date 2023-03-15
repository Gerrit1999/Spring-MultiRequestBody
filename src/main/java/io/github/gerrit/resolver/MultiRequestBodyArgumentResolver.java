package io.github.gerrit.resolver;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ClassUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gerrit.annotation.MultiRequestBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.annotation.ValidationAnnotationUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * MultiRequestBody解析器<br>
 * 解决的问题：<br>
 * 1、单个字符串等包装类型都要写一个对象才可以用@RequestBody接收<br>
 * 2、多个对象需要封装到一个对象里才可以用@RequestBody接收<br>
 * 主要优势：<br>
 * 1、支持通过注解的value指定Json的key来解析对象<br>
 * 2、支持通过注解无value，直接根据参数名来解析对象<br>
 * 3、支持基本类型的注入<br>
 * 4、支持GET和其他请求方式注入<br>
 * 5、支持通过注解无value且参数名不匹配Json串key时，根据属性解析对象<br>
 * 6、支持多余属性(不解析、不报错)、支持参数“共用”（不指定value时，参数名不为Json串的key）<br>
 * 7、支持当value和属性名找不到匹配的key时，对象是否匹配所有属性<br>
 *
 * @author Wangyang Liu  QQ: 605283073
 * @date 2018/08/27
 */
public class MultiRequestBodyArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String JSON_BODY_ATTRIBUTE = "JSON_REQUEST_BODY";

    private static final String ILLEGAL_ARGUMENT_MESSAGE_TEMPLATE = "required param %s is not present";

    private static final String NOT_NULL_MESSAGE_TEMPLATE = "%s must not be null";

    /**
     * 设置支持的方法参数类型
     *
     * @param parameter 方法参数
     * @return 支持的类型
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // 支持带@MultiRequestBody注解的参数
        return parameter.hasParameterAnnotation(MultiRequestBody.class);
    }

    /**
     * 参数解析
     * 注意：非基本类型返回null会报空指针异常，要通过反射或者Json工具类创建一个空对象
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        Object arg = resolveArgument(webRequest, parameter);
        String name = parameter.getParameterName();
        Assert.notNull(name, String.format(NOT_NULL_MESSAGE_TEMPLATE, "parameterName"));
        if (binderFactory != null) {
            WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name);
            if (arg != null) {
                this.validateIfApplicable(binder, parameter);
                if (binder.getBindingResult().hasErrors() && this.isBindExceptionRequired(binder, parameter)) {
                    throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
                }
            }
            if (mavContainer != null) {
                mavContainer.addAttribute(BindingResult.MODEL_KEY_PREFIX + name, binder.getBindingResult());
            }
        }
        return arg;
    }

    private Object resolveArgument(NativeWebRequest webRequest, MethodParameter parameter) throws IllegalAccessException, JsonProcessingException {
        ObjectMapper om = new ObjectMapper();
        String jsonBody = this.getRequestBody(webRequest);
        JsonNode jsonNode = om.readTree(jsonBody);
        // 根据@MultiRequestBody注解value作为json解析的key
        MultiRequestBody multiRequestBody = parameter.getParameterAnnotation(MultiRequestBody.class);
        // 注解的value是Json的key
        Assert.notNull(multiRequestBody, String.format(NOT_NULL_MESSAGE_TEMPLATE, MultiRequestBody.class.getName()));
        String key = multiRequestBody.value();
        JsonNode value;
        if (StringUtils.hasLength(key)) {
            // 如果设置了key但是解析不到，报错
            if (!jsonNode.has(key) && multiRequestBody.required()) {
                throw new IllegalArgumentException(String.format(ILLEGAL_ARGUMENT_MESSAGE_TEMPLATE, key));
            }
        } else {
            // 如果注解没有设置key，则取参数名作为Json解析的key
            key = parameter.getParameterName();
        }

        // 获取参数类型
        Class<?> parameterType = parameter.getParameterType();

        // 获取参数值
        value = jsonNode.get(key);

        // 能拿到值，进行解析
        if (value != null) {
            if (ClassUtil.isBasicType(parameterType)) {
                // 基本类型及包装类
                return this.parseBasicType(parameterType, value);
            } else if (String.class.equals(parameterType)) {
                // 字符串类型
                return value.asText();
            }
            // 其他复杂对象
            return om.readValue(value.toString(), parameterType);
        }

        if (ClassUtil.isBasicType(parameterType) ||
                Iterable.class.isAssignableFrom(parameterType) ||
                !multiRequestBody.parseAllFields()) {
            // 为基本类型、包装类、可迭代集合、不允许解析所有字段
            // 是基本类型或必要参数报错，否则返回null
            if (parameterType.isPrimitive() || multiRequestBody.required()) {
                throw new IllegalArgumentException(String.format(ILLEGAL_ARGUMENT_MESSAGE_TEMPLATE, key));
            } else {
                return null;
            }
        } else {
            Object result = om.readValue(jsonBody, parameterType);
            // 如果是非必要参数或Map，直接返回，否则如果所有属性都为null则报错
            if (multiRequestBody.required() && !Map.class.isAssignableFrom(parameterType)) {
                boolean haveValue = false;
                Field[] declaredFields = parameterType.getDeclaredFields();
                for (Field field : declaredFields) {
                    field.setAccessible(true);
                    if (field.get(result) != null) {
                        haveValue = true;
                        break;
                    }
                }
                if (!haveValue) {
                    throw new IllegalArgumentException(String.format(ILLEGAL_ARGUMENT_MESSAGE_TEMPLATE, key));
                }
            }
            return result;
        }
    }

    /**
     * 基本类型及包装类解析
     */
    private Object parseBasicType(Class<?> type, JsonNode value) {
        if (int.class.equals(type) || Integer.class.equals(type)) {
            return value.intValue();
        } else if (short.class.equals(type) || Short.class.equals(type)) {
            return value.shortValue();
        } else if (long.class.equals(type) || Long.class.equals(type)) {
            return value.longValue();
        } else if (float.class.equals(type) || Float.class.equals(type)) {
            return value.floatValue();
        } else if (double.class.equals(type) || Double.class.equals(type)) {
            return value.doubleValue();
        } else if (byte.class.equals(type) || Byte.class.equals(type)) {
            return ((byte) value.intValue());
        } else if (boolean.class.equals(type) || Boolean.class.equals(type)) {
            return value.asBoolean();
        } else if (char.class.equals(type) || Character.class.equals(type)) {
            String s = value.asText();
            return StringUtils.hasLength(s) ? s.charAt(0) : null;
        }
        return null;
    }

    /**
     * 获取请求体Json字符串
     */
    private String getRequestBody(NativeWebRequest webRequest) {
        HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
        Assert.notNull(servletRequest, String.format(NOT_NULL_MESSAGE_TEMPLATE, HttpServletRequest.class.getName()));
        // 有就直接获取
        String jsonBody = (String) webRequest.getAttribute(JSON_BODY_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
        // 没有就从请求中读取
        if (jsonBody == null) {
            try (BufferedReader reader = servletRequest.getReader()) {
                Assert.notNull(reader, String.format(NOT_NULL_MESSAGE_TEMPLATE, BufferedReader.class.getName()));
                jsonBody = IoUtil.read(reader);
                webRequest.setAttribute(JSON_BODY_ATTRIBUTE, jsonBody, NativeWebRequest.SCOPE_REQUEST);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return jsonBody;
    }

    /**
     * Validate the binding target if applicable.
     * <p>The default implementation checks for {@code @javax.validation.Valid},
     * Spring's {@link org.springframework.validation.annotation.Validated},
     * and custom annotations whose name starts with "Valid".
     *
     * @param binder    the DataBinder to be used
     * @param parameter the method parameter descriptor
     * @see #isBindExceptionRequired
     * @since 4.1.5
     */
    protected void validateIfApplicable(WebDataBinder binder, MethodParameter parameter) {
        Annotation[] annotations = parameter.getParameterAnnotations();
        for (Annotation ann : annotations) {
            Object[] validationHints = ValidationAnnotationUtils.determineValidationHints(ann);
            if (validationHints != null) {
                binder.validate(validationHints);
                break;
            }
        }
    }

    /**
     * Whether to raise a fatal bind exception on validation errors.
     *
     * @param binder    the data binder used to perform data binding
     * @param parameter the method parameter descriptor
     * @return {@code true} if the next method argument is not of type {@link Errors}
     * @since 4.1.5
     */
    protected boolean isBindExceptionRequired(WebDataBinder binder, MethodParameter parameter) {
        int i = parameter.getParameterIndex();
        Class<?>[] paramTypes = parameter.getExecutable().getParameterTypes();
        boolean hasBindingResult = (paramTypes.length > (i + 1) && Errors.class.isAssignableFrom(paramTypes[i + 1]));
        return !hasBindingResult;
    }
}
