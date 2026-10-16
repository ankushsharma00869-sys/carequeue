package com.carequeue.security;

import com.carequeue.entity.Doctor;
import com.carequeue.entity.Patient;
import com.carequeue.entity.User;
import com.carequeue.repository.DoctorRepository;
import com.carequeue.repository.PatientRepository;
import com.carequeue.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final DoctorRepository doctorRepository;
    private final PatientRepository patientRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        Long doctorId = doctorRepository.findByUserId(user.getId()).map(Doctor::getId).orElse(null);
        Long patientId = patientRepository.findByUserId(user.getId()).map(Patient::getId).orElse(null);

        return UserPrincipal.create(user, doctorId, patientId);
    }
}
