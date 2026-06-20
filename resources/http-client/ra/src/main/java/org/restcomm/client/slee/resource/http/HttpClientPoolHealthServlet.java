package org.restcomm.client.slee.resource.http;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * JSON health endpoint: GET /restcomm/health/http-client
 */
public class HttpClientPoolHealthServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpClientPoolHealthSnapshot snapshot = HttpClientHealthRegistry.getSnapshot();
        resp.setStatus(snapshot.getHttpStatusCode());
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        PrintWriter writer = resp.getWriter();
        writer.write(snapshot.toJson());
        writer.flush();
    }
}
