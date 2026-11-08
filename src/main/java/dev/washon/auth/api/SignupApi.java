package dev.washon.auth.api;

import dev.washon.auth.application.SignupService;
import dev.washon.auth.payload.request.SignupCodeRequest;
import dev.washon.auth.payload.request.SignupCodeVerifyRequest;
import dev.washon.auth.payload.request.SignupRequest;
import dev.washon.auth.payload.response.SignupResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Student sign-up (no session needed). See {@link SignupService} for the status codes. */
@RestController
@RequestMapping("/auth/signup")
public class SignupApi {

    private final SignupService signupService;

    public SignupApi(SignupService signupService) {
        this.signupService = signupService;
    }

    /** Emails a sign-up code; 204 once sent. */
    @PostMapping("/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendCode(@RequestBody SignupCodeRequest request) {
        signupService.sendCode(request);
    }

    /** Checks a code without using it up; 204 when right, 422 when wrong or expired. */
    @PostMapping("/code/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyCode(@RequestBody SignupCodeVerifyRequest request) {
        signupService.verifyCode(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@RequestBody SignupRequest request) {
        return signupService.signup(request);
    }
}
