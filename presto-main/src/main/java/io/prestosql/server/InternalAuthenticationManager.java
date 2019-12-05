/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.server;

import com.google.common.hash.Hashing;
import io.airlift.http.client.HttpRequestFilter;
import io.airlift.http.client.Request;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.prestosql.server.security.InternalPrincipal;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Optional;

import static io.airlift.http.client.Request.Builder.fromRequest;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

public class InternalAuthenticationManager
        implements HttpRequestFilter
{
    private static final Logger log = Logger.get(InternalAuthenticationManager.class);

    private static final String PRESTO_INTERNAL_BEARER = "X-Presto-Internal-Bearer";

    private final boolean internalJwtEnabled;
    private final byte[] hmac;
    private final String nodeId;

    @Inject
    public InternalAuthenticationManager(InternalCommunicationConfig internalCommunicationConfig, NodeInfo nodeInfo)
    {
        this(requireNonNull(internalCommunicationConfig, "internalCommunicationConfig is null").getSharedSecret(), nodeInfo.getNodeId());
    }

    public InternalAuthenticationManager(Optional<String> sharedSecret, String nodeId)
    {
        requireNonNull(sharedSecret, "sharedSecret is null");
        requireNonNull(nodeId, "nodeId is null");
        this.internalJwtEnabled = sharedSecret.isPresent();
        if (internalJwtEnabled) {
            this.hmac = Hashing.sha256().hashString(sharedSecret.get(), UTF_8).asBytes();
        }
        else {
            this.hmac = null;
        }
        this.nodeId = nodeId;
    }

    private String generateJwt()
    {
        return Jwts.builder()
                .signWith(SignatureAlgorithm.HS256, hmac)
                .setSubject(nodeId)
                .setExpiration(Date.from(ZonedDateTime.now().plusMinutes(5).toInstant()))
                .compact();
    }

    private String parseJwt(String jwt)
    {
        return Jwts.parser()
                .setSigningKey(hmac)
                .parseClaimsJws(jwt)
                .getBody()
                .getSubject();
    }

    public boolean isInternalRequest(HttpServletRequest request)
    {
        return request.getHeader(PRESTO_INTERNAL_BEARER) != null;
    }

    public Principal authenticateInternalRequest(HttpServletRequest request)
    {
        if (!internalJwtEnabled) {
            log.error("Internal authentication in not configured");
            return null;
        }

        String internalBarer = request.getHeader(PRESTO_INTERNAL_BEARER);
        try {
            String subject = parseJwt(internalBarer);
            return new InternalPrincipal(subject);
        }
        catch (JwtException e) {
            log.error(e, "Internal authentication failed");
            return null;
        }
        catch (RuntimeException e) {
            throw new RuntimeException("Authentication error", e);
        }
    }

    @Override
    public Request filterRequest(Request request)
    {
        if (!internalJwtEnabled) {
            return request;
        }

        return fromRequest(request)
                .addHeader(PRESTO_INTERNAL_BEARER, generateJwt())
                .build();
    }
}
