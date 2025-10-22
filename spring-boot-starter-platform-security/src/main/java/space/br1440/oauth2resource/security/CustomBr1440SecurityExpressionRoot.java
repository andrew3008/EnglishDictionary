package space.br1440.oauth2resource.security;

import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.core.Authentication;

public class CustomBr1440SecurityExpressionRoot extends SecurityExpressionRoot implements MethodSecurityExpressionOperations {


    private final PermissionFileEvaluator permissionFileEvaluator;
    private Object filterObject;
    private Object returnObject;
    private Object target;

    public CustomBr1440SecurityExpressionRoot(Authentication authentication, PermissionFileEvaluator permissionFileEvaluator) {
        super(authentication);
        this.permissionFileEvaluator = permissionFileEvaluator;
    }


    public boolean hasPermission(String permission) {
        return permissionFileEvaluator.hasPermission(getAuthentication(), permission);
    }

    // Дополнительные кастомные методы
    public boolean hasAnyPermission(String... permissions) {
        return permissionFileEvaluator.hasAnyPermission(getAuthentication(), permissions);
    }


    @Override
    public void setFilterObject(Object filterObject) {
        this.filterObject = filterObject;
    }

    @Override
    public Object getFilterObject() {
        return filterObject;
    }

    @Override
    public void setReturnObject(Object returnObject) {
        this.returnObject = returnObject;
    }

    @Override
    public Object getReturnObject() {
        return returnObject;
    }

    @Override
    public Object getThis() {
        return target;
    }
}
