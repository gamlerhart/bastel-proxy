package bastel_proxy;

import clojure.lang.IFn;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

public class RequestFilter implements Filter {

    /**
     * Rewrite the request before it is processed
     */
    private final IFn requestRewrite;

    public RequestFilter(IFn requestRewrite) {
        this.requestRewrite = requestRewrite;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest) {
            HttpServletRequest original = (HttpServletRequest) request;
            FilterResult rewritten = (FilterResult) requestRewrite.invoke(original, response);
            if (rewritten.responseSent) {
                // Done
            } else if (rewritten.request != null) {
                chain.doFilter(rewritten.request, response);
            } else {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {

    }

    public static class FilterResult {
        public final ServletRequest request;
        public final boolean responseSent;

        public FilterResult(ServletRequest request, boolean responseSent) {
            this.responseSent = responseSent;
            this.request = request;
        }
    }
}
