package space.br1440.oauth2resource.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

public class CustomBr1440MethodSecurityExpressionHandler extends DefaultMethodSecurityExpressionHandler {

    private final PermissionFileEvaluator permissionFileEvaluator;
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    public CustomBr1440MethodSecurityExpressionHandler(PermissionFileEvaluator permissionFileEvaluator) {
        this.permissionFileEvaluator = permissionFileEvaluator;
    }


    @Override
    public EvaluationContext createEvaluationContext(Supplier<Authentication> authentication, MethodInvocation mi) {
        StandardEvaluationContext context = (StandardEvaluationContext) super.createEvaluationContext(authentication, mi);
        CustomBr1440SecurityExpressionRoot root = new CustomBr1440SecurityExpressionRoot(authentication.get(), permissionFileEvaluator);
        root.setPermissionEvaluator(getPermissionEvaluator());
        root.setTrustResolver(this.trustResolver);
        root.setRoleHierarchy(getRoleHierarchy());
        context.setRootObject(root);
        return context;
    }
}
