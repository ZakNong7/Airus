# Airus - Premium Android Music Player

**Airus** adalah aplikasi pemutar musik tingkat lanjut untuk platform Android yang menggabungkan keindahan antarmuka modern dengan performa pemrosesan audio tingkat rendah (low-level). Fokus utama Airus adalah memberikan kualitas audio terbaik tanpa kompromi, terutama bagi para pecinta audio *lossless*.

---

## 🚀 Fitur Unggulan

### 🎧 Performa Audio Tinggi
*   **Native FLAC Decoder**: Menggunakan implementasi C++ dengan `libFLAC` melalui JNI (Java Native Interface) untuk decoding audio yang sangat efisien dan akurat secara bit.
*   **Bit-perfect Playback**: Jalur pemrosesan audio yang dioptimalkan untuk menjaga integritas sinyal asli.

### 🎨 Antarmuka Pengguna Modern
*   **Jetpack Compose & Material 3**: UI yang sepenuhnya deklaratif, responsif, dan mendukung tema dinamis (Material You).
*   **Navigasi Intuitif**: Transisi antar layar yang mulus untuk pengalaman pengguna yang menyenangkan.

### 📂 Manajemen Perpustakaan Pintar
*   **Database Room**: Sinkronisasi cepat antara file media di perangkat dengan database aplikasi.
*   **Pengorganisasian Otomatis**: Klasifikasi berdasarkan Lagu, Album, Artis, dan Genre.
*   **Playlist Kustom**: Buat dan kelola daftar putar favorit Anda dengan mudah.

### 🎚️ Kontrol Audio
*   **Equalizer Terintegrasi**: Sesuaikan karakteristik suara dengan equalizer multi-band dan simpan sebagai preset kustom.

---

## 🛠️ Arsitektur Teknologi

Airus dibangun dengan prinsip pengembangan Android modern:

*   **Bahasa Utama**: Kotlin (UI & Logic) dan C++ (Audio Engine).
*   **Arsitektur**: MVVM (Model-View-ViewModel) dengan Clean Architecture principles.
*   **Dependency Injection**: (Jika ada, misal Hilt/Koin).
*   **Local Storage**: Room Persistence Library untuk metadata dan preferensi.
*   **Native Development Kit (NDK)**: Digunakan untuk mengelola `libFLAC` agar performa decoding maksimal.

---

## 📁 Struktur Proyek (Penting)

*   `app/src/main/cpp/`: Implementasi native decoder (C++).
*   `app/src/main/java/.../database/`: Entitas dan DAO untuk Room.
*   `app/src/main/java/.../ui/`: Komponen UI berbasis Jetpack Compose.
*   `app/src/main/res/`: Sumber daya aplikasi (icons, layouts, values).

---

## ⚙️ Persyaratan Sistem & Instalasi

### Prasyarat
*   Android Studio Ladybug (2024.2.1) atau versi lebih baru.
*   Android SDK 34+.
*   Android NDK (untuk kompilasi C++).
*   Gradle 8.0+.

### Langkah Instalasi
1.  Clone repositori ini:
    ```bash
    git clone https://github.com/ZakNong7/Airus.git
    ```
2.  Buka proyek di Android Studio.
3.  Tunggu sinkronisasi Gradle selesai.
4.  Hubungkan perangkat Android atau jalankan emulator.
5.  Klik **Run 'app'**.

---

## 📝 Kontribusi
Kontribusi selalu terbuka! Jika Anda menemukan bug atau memiliki ide fitur baru, silakan buka *Issue* atau kirimkan *Pull Request*.

## 📄 Lisensi
Proyek ini dilisensikan di bawah [MIT License](LICENSE).

---
**Airus** - *Experience Music the Right Way.*
Dibuat oleh [ZakNong7](https://github.com/ZakNong7)
