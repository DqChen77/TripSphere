package org.tripsphere.user.api.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;
import org.tripsphere.user.exception.NotFoundException;
import org.tripsphere.user.security.AuthContext;
import org.tripsphere.user.service.UserService;
import org.tripsphere.user.v1.GetCurrentUserRequest;
import org.tripsphere.user.v1.GetCurrentUserResponse;
import org.tripsphere.user.v1.SignInRequest;
import org.tripsphere.user.v1.SignInResponse;
import org.tripsphere.user.v1.SignUpRequest;
import org.tripsphere.user.v1.SignUpResponse;
import org.tripsphere.user.v1.User;
import org.tripsphere.user.v1.UserServiceGrpc;

@GrpcService
@RequiredArgsConstructor
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    private final UserService userService;
    private final AuthContext authContext;

    @Override
    public void signUp(SignUpRequest request, StreamObserver<SignUpResponse> responseObserver) {
        String name = request.getName();
        String email = request.getEmail();
        String password = request.getPassword();

        userService.signUp(name, email, password);

        responseObserver.onNext(SignUpResponse.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void signIn(SignInRequest request, StreamObserver<SignInResponse> responseObserver) {
        String email = request.getEmail();
        String password = request.getPassword();

        UserService.SignInResult result = userService.signIn(email, password);

        SignInResponse response =
                SignInResponse.newBuilder().setUser(result.user()).setToken(result.token()).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getCurrentUser(
            GetCurrentUserRequest request,
            StreamObserver<GetCurrentUserResponse> responseObserver) {
        String email = authContext.getEmail();

        User user =
                userService
                        .findByEmail(email)
                        .orElseThrow(() -> new NotFoundException("User", email));

        GetCurrentUserResponse response = GetCurrentUserResponse.newBuilder().setUser(user).build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
