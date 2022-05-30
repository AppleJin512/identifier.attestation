package id.attestation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.attestation.data.*;
import id.attestation.exception.IllegalAttestationRequestException;
import id.attestation.exception.NoRecapthaResponseException;
import id.attestation.exception.RecaptchaVerifyFailedException;
import id.attestation.service.auth.AuthenticationService;
import id.attestation.service.email.EmailData;
import id.attestation.service.email.EmailService;
import id.attestation.service.email.EmailTemplate;
import id.attestation.service.plugin.AuthenticationServiceManager;
import id.attestation.service.plugin.PluginService;
import id.attestation.service.recaptcha.RecaptchaService;
import id.attestation.utils.CryptoUtils;
import id.attestation.utils.IdentifierExtractor;
import id.attestation.utils.MagicLinkParser;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;
import org.devcon.ticket.Ticket;
import org.devcon.ticket.TicketDecoder;
import org.pf4j.PluginWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tokenscript.attestation.ERC721Token;
import org.tokenscript.attestation.IdentifierAttestation.AttestationType;
import org.tokenscript.attestation.ValidationTools;
import org.tokenscript.attestation.core.AttestationCrypto;
import org.tokenscript.attestation.core.DERUtility;
import org.tokenscript.attestation.core.URLUtility;
import org.tokenscript.attestation.eip712.Eip712AttestationRequest;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.io.IOException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Controller("/api")
public class ApiController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiController.class);
    public static final String ATTESTOR_DOMAIN = "http://wwww.attestation.id";

    private final EmailService emailService;
    private final RecaptchaService recaptchaService;
    private final String recaptchaKey;
    private final AsymmetricCipherKeyPair keys;
    private final String devconPubkeyHex;
    private TicketDecoder ticketDecoder;

    @Inject
    private AuthenticationServiceManager authenticationServiceManager;

    @Inject
    private PluginService pluginService;

    public ApiController(@Value("${RECAPTCHA_KEY:none}") String recaptchaKey
            , @Value("${ATTESTOR_PRIVATE_KEY:none}") String attestorPrivateKey
            , @Value("${DEVCON_PUBLIC_KEY:none}") String devconPubkeyHex
            , EmailService emailService
            , RecaptchaService recaptchaService
    ) {
        this.recaptchaKey = recaptchaKey;
        this.emailService = emailService;
        this.recaptchaService = recaptchaService;
        // The string list expected by DERUtility.restoreBase64Keys should have the following format:
        // [prefix, content, postfix]
        // Only content is needed.
        this.keys = DERUtility.restoreBase64Keys(Arrays.asList("", attestorPrivateKey, ""));
        this.devconPubkeyHex = devconPubkeyHex;
    }

    @Consumes({MediaType.APPLICATION_FORM_URLENCODED, MediaType.APPLICATION_JSON})
    @Post("/otp")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<OtpResponse> sendOTP(@Body @Valid OtpRequest request, @Body("g-recaptcha-response") String recaptchaResponse) {
        verifyRecapthca(recaptchaResponse);
        String otp = CryptoUtils.generateOtp();
        OtpResponse response = new OtpResponse(CryptoUtils.encryptWithRSAOAEP(request.getPublicKey(), otp));
        if ("mail".equalsIgnoreCase(request.getType())) {
            emailService.send(new EmailData(request.getValue(), "Your OTP for Attestation Request", EmailTemplate.createEmailBody(otp)));
        }
        return HttpResponse.created(response);
    }

    /**
     * @param request AttestationWebRequest
     * @return AttestationWebResponse
     */
    @Post("/attestation/")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<AttestationWebResponse> createAttestation(
            @Body @Valid AttestationWebRequest request, HttpHeaders headers) {
        final String idProvider = headers.get("x-pap-id-provider");
        if (idProvider == null) {
            throw new IllegalAttestationRequestException("Missing x-pap-id-provider in header");
        }
        AuthenticationService authenticationService = authenticationServiceManager.findByIdProvider(idProvider)
                .orElseThrow(() -> new IllegalAttestationRequestException("unsupported idProvider: " + idProvider));
        String email;
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode publicRequestNode = mapper.readTree(request.getPublicRequest());
            JsonNode jsonSignedNode = publicRequestNode.get("jsonSigned");
            email = mapper.readTree(jsonSignedNode.textValue()).get("message").get("identifier").textValue();
        } catch (Exception e) {
            LOGGER.error("wrong publicRequest", e);
            throw new IllegalAttestationRequestException("wrong publicRequest");
        }
        Map<String, List<String>> filteredHeaders = authenticationServiceManager.filterRequiredHeaders(headers);
        if (!authenticationService.verifyEmail(filteredHeaders, email)) {
            throw new IllegalAttestationRequestException("email could not be verified.");
        }
        Eip712AttestationRequest attestationRequest = createAttestRequest(request);
        return HttpResponse.created(CryptoUtils.constructAttest(request.getValidity(), request.getAttestor(), attestationRequest, keys));
    }

    @Post("/attestation/magic-link")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<AttestationWebResponse> createMagicLinkAttestation(@Body @Valid MagicLinkAttestationWebRequest request) {
        MagicLink magicLink = MagicLinkParser.parse(request.getMagicLink());
        Ticket ticket = decodeTicket(magicLink.getEncodedTicket());
        String email = IdentifierExtractor.extract(request.getPublicRequest());
        if (isEmailDifferent(email, magicLink.getSecret(), ticket)) {
            throw new IllegalAttestationRequestException("Email is different from that in ticket commitment");
        }
        Eip712AttestationRequest attestationRequest = createAttestRequest(request);
        return HttpResponse.created(CryptoUtils.constructAttest(request.getValidity(), request.getAttestor(), attestationRequest, keys));
    }

    @Post("/attestation/public")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<PublicAttestationWebResponse> createPublicAttestation(
            @Body @Valid PublicAttestationWebRequest request, HttpHeaders headers) {
        if (!isHex(request.getMessage()) || !isHex(request.getSignature().substring(2))) {
            throw new IllegalAttestationRequestException("message or signature is not hex string.");
        }

        if (!ValidationTools.isAddress(request.getEthAddress())) {
            throw new IllegalAttestationRequestException("bad address");
        }

        String paProvider;
        try {
            String domain = new URL(request.getIdentifier().split("\\s+")[0]).getHost();
            // twitter.com --> twitter, www.facebook.com --> facebook
            String[] domainSplit = domain.split("\\.");
            paProvider = domainSplit[domainSplit.length - 2];
        } catch (MalformedURLException e) {
            throw new IllegalAttestationRequestException("invalid identifier format");
        }

        // fallback to auth0 for backward compatibility
        final String idProvider = Objects.requireNonNullElse(headers.get("x-pap-id-provider"), "auth0");
        AuthenticationService authenticationService = authenticationServiceManager.findByIdProvider(idProvider)
                .orElseThrow(() -> new IllegalAttestationRequestException("unsupported idProvider: " + idProvider));

        LOGGER.debug("AuthenticationService in use: {}, matched idProvider {} to verify paProvider {}"
                , authenticationService.getClass().getName(), idProvider, paProvider);

        Map<String, List<String>> filteredHeaders = authenticationServiceManager.filterRequiredHeaders(headers);
        if (!authenticationService.verifySocialConnection(filteredHeaders, paProvider, request.getId())) {
            throw new IllegalAttestationRequestException("identifier could not be verified.");
        }

        return HttpResponse.created(CryptoUtils.constructPublicAttest(request.getId()
                , request.getIdentifier(), request.getMessage(), request.getSignature(), request.getEthAddress(), keys));
    }

    @Post("/attestation/nft")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<AttestationWebResponse> createNftAttestation(@Body @Valid NftAttestationWebRequest request) {
        NftParam[] nfts = request.getNfts();
        ERC721Token[] tokens = new ERC721Token[nfts.length];
        for (int index = 0; index < nfts.length; index++) {
            if (!ValidationTools.isAddress(nfts[index].address)) {
                throw new IllegalAttestationRequestException("bad address");
            }
            tokens[index] = nfts[index].toERC721Token();
        }
        return HttpResponse.created(CryptoUtils.constructNftAttest(keys.getPublic()
                , request.getPublicAttestation(), tokens));
    }

    @Post("/attestation/signed-nft")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<AttestationWebResponse> createSignedNftAttestation(@Body @Valid SignedNftAttestationWebRequest request) {
        return HttpResponse.created(CryptoUtils.constructSignedNftAttest(request.getPublicKey(), keys.getPublic()
                , request.getNftAttestation(), request.getSignature()));
    }

    @Post("/attestation/cosigned")
    @ExecuteOn(TaskExecutors.IO)
    public HttpResponse<AttestationWebResponse> createCoSignedIdentifierAttestation(@Body @Valid CoSignedIdentifierAttestationWebRequest request) {
        return HttpResponse.created(CryptoUtils.constructCoSignedIdentifierAttest(request.getPublicKey(), keys.getPublic()
                , request.getPublicAttestation(), request.getSignature()));
    }

    @Get("/health")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, List<Object>> health() {
        List<PluginWrapper> plugins = pluginService.getPlugins();
        List<AuthenticationService> authenticationServices = authenticationServiceManager.getAll();
        return Map.of(
                "plugins", plugins.stream()
                        .map(plugin -> Map.of(
                                "id", plugin.getPluginId()
                                , "version", plugin.getDescriptor().getVersion()))
                        .collect(Collectors.toList())
                , "AuthenticationService", authenticationServices.stream()
                        .map(service -> Map.of(
                                "idProvider", service.idProvider()
                                , "class", service.getClass().getName()))
                        .collect(Collectors.toList())
        );
    }

    private Eip712AttestationRequest createAttestRequest(AttestationWebRequest request) {
        Eip712AttestationRequest attestationRequest;
        try {
            attestationRequest = new Eip712AttestationRequest(ATTESTOR_DOMAIN, request.getPublicRequest());
            if (!attestationRequest.checkValidity() || !attestationRequest.verify()) {
                throw new RuntimeException("Could not validate attestation signing request");
            }
            return attestationRequest;
        } catch (Exception e) {
            LOGGER.error("Can not create an AttestationRequest from request, caused by:", e);
            throw new IllegalAttestationRequestException(e.getMessage());
        }
    }

    private boolean verifyRecapthca(String recaptchaResponse) {
        if (StringUtils.isEmpty(recaptchaResponse)) {
            throw new NoRecapthaResponseException("Missing client recaptcha response.");
        }

        try {
            Map<String, ?> result = Mono.from(recaptchaService.verify(recaptchaKey, recaptchaResponse)).block();
            if (result != null && (Boolean) result.get("success")) {
                LOGGER.info("***** reCaptcha verified successfully *****");
                return true;
            } else {
                @SuppressWarnings("unchecked")
                String errorCodes = String.join(",", ((List<String>) result.get("error-codes")));
                LOGGER.error("Recaptcha verify failed, caused by: {}",
                        errorCodes);
                throw new RecaptchaVerifyFailedException(
                        "Recaptcha verify failed, cause:" + errorCodes);
            }
        } catch (NullPointerException e) {
            throw new RecaptchaVerifyFailedException(
                    "Recaptcha verify failed: Could not retrieve recaptcha result");
        }
    }

    private boolean isHex(String value) {
        try {
            Hex.decodeStrict(value);
            return true;
        } catch (DecoderException var2) {
            return false;
        }
    }

    private Ticket decodeTicket(String encodedTicket) {
        if (ticketDecoder == null) {
            ticketDecoder = new TicketDecoder(CryptoUtils.getECPublicKeyParameters(devconPubkeyHex));
        }
        try {
            Ticket ticket = ticketDecoder.decode(URLUtility.decodeData(encodedTicket));
            if (!ticket.verify() || !ticket.checkValidity()) {
                throw new IllegalAttestationRequestException("Could decode a valid or verifiable ticket");
            }
            return ticket;
        } catch (IOException e) {
            LOGGER.error("failed to decode the ticket, caused by: {}", e.getMessage());
            throw new IllegalAttestationRequestException("failed to decode ticket, cause: " + e.getMessage());
        }
    }

    private boolean isEmailDifferent(String email, BigInteger secret, Ticket ticket) {
        byte[] commitment = AttestationCrypto.makeCommitment(email, AttestationType.EMAIL, secret);
        return !Arrays.equals(commitment, ticket.getCommitment());
    }

}
